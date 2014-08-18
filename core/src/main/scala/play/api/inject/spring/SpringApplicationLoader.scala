package play.api.inject.spring

import java.lang.annotation.Annotation
import javax.inject.{Scope, Provider}

import org.springframework.beans.TypeConverter
import org.springframework.beans.factory.annotation.{AutowiredAnnotationBeanPostProcessor, QualifierAnnotationAutowireCandidateResolver}
import org.springframework.beans.factory.{NoUniqueBeanDefinitionException, NoSuchBeanDefinitionException, FactoryBean}
import org.springframework.beans.factory.config.{AutowireCapableBeanFactory, BeanDefinitionHolder, ConstructorArgumentValues, BeanDefinition}
import org.springframework.beans.factory.support._
import org.springframework.context.ApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.annotation.AnnotationUtils
import play.api.inject._
import play.api._
import play.api.ApplicationLoader.Context
import play.core.WebCommands

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * Spring application loader
 */
class SpringApplicationLoader extends ApplicationLoader {
  def load(context: Context) = {
    val env = context.environment

    // Create the global first
    val global = GlobalSettings(context.initialConfiguration, env)

    // Create the final configuration
    // todo - abstract this logic out into something pluggable, with the default delegating to global
    val configuration = global.onLoadConfig(context.initialConfiguration, env.rootPath, env.classLoader, env.mode)

    Logger.configure(env.rootPath, configuration, env.mode)

    // Load modules and include some of the core bindings
    val modules = new Module {
      def bindings(environment: Environment, configuration: Configuration) = Seq(
      BindingKey(classOf[GlobalSettings]) to global,
      BindingKey(classOf[OptionalSourceMapper]) to new OptionalSourceMapper(context.sourceMapper),
      BindingKey(classOf[WebCommands]) to context.webCommands
    )} +: Modules.locate(env, configuration)

    val ctx = createApplicationContext(env, configuration, modules)
    ctx.getBean(classOf[Application])
  }

  override def createInjector(environment: Environment, configuration: Configuration, modules: Seq[Any]): Option[Injector] = {
    Some(createApplicationContext(environment, configuration, modules).getBean(classOf[Injector]))
  }

  /**
   * Creates an application context for the given modules
   */
  private[spring] def createApplicationContext(environment: Environment, configuration: Configuration, modules: Seq[Any]): ApplicationContext = {

    // todo, use an xml or classpath scanning context or something not dumb
    val ctx = new GenericApplicationContext()
    val beanFactory = ctx.getDefaultListableBeanFactory
    beanFactory.setAutowireCandidateResolver(new QualifierAnnotationAutowireCandidateResolver())


    // Register the Spring injector as a singleton first
    beanFactory.registerSingleton("play-injector", new SpringInjector(beanFactory))

    modules.foreach {
      case playModule: Module => playModule.bindings(environment, configuration).foreach(b => bind(beanFactory, b))
      case unknown => throw new PlayException(
        "Unknown module type",
        s"Module [$unknown] is not a Play module or a Guice module"
      )
    }

    ctx.refresh()
    ctx.start()
    ctx
  }

  /**
   * Perhaps this method should be moved into a custom bean definition reader - eg a PlayModuleBeanDefinitionReader.
   */
  private def bind(beanFactory: DefaultListableBeanFactory, binding: Binding[_]) = {

    // Firstly, if it's an unqualified key being bound to an unqualified alias, then there is no need to
    // register anything, Spring by type lookups match all types of the registered bean, there is no need
    // to register aliases for other types.
    val isSimpleTypeAlias = binding.key.qualifier.isEmpty &&
      binding.target.collect {
        case b @ BindingKeyTarget(key) if key.qualifier.isEmpty => b
      }.nonEmpty

    if (!isSimpleTypeAlias) {

      val beanDef = new GenericBeanDefinition()

      // todo - come up with a better name
      val beanName = binding.key.toString()

      // Add qualifier if it exists
      binding.key.qualifier match {
        case Some(QualifierClass(clazz)) =>
          beanDef.addQualifier(new AutowireCandidateQualifier(clazz))
        case Some(QualifierInstance(instance)) =>
          beanDef.addQualifier(qualifierFromInstance(instance))
        case None =>
          // No qualifier, so that means this is the primary binding for that type.
          // Setting primary means that if there are both qualified beans and this bean for the same type,
          // when an unqualified lookup is done, this one will be selected.
          beanDef.setPrimary(true)
      }

      // Start with a scope of prototype, if it's singleton, we'll explicitly set that later
      beanDef.setScope(BeanDefinition.SCOPE_PROTOTYPE)
      // Choose autowire constructor
      beanDef.setAutowireMode(AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR)

      binding.target match {
        case None =>
          // Bound to itself, set the key class as the bean class
          beanDef.setBeanClass(binding.key.clazz)
          SpringApplicationLoader.maybeSetScope(beanDef, binding.key.clazz)

        case Some(ConstructionTarget(clazz)) =>
          // Bound to an implementation, set the impl class as the bean class.
          // In this case, the key class is ignored, since Spring does not key beans by type, but a bean is eligible
          // for autowiring for all supertypes/interafaces.
          beanDef.setBeanClass(clazz.asInstanceOf[Class[_]])
          SpringApplicationLoader.maybeSetScope(beanDef, clazz.asInstanceOf[Class[_]])

        case Some(ProviderConstructionTarget(providerClass)) =>

          // The provider itself becomes a bean that gets autowired
          val providerBeanDef = new GenericBeanDefinition()
          providerBeanDef.setBeanClass(providerClass)
          providerBeanDef.setAutowireMode(AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR)
          providerBeanDef.setScope(BeanDefinition.SCOPE_SINGLETON)
          providerBeanDef.setAutowireCandidate(false)
          val providerBeanName = beanName + "-provider"
          beanFactory.registerBeanDefinition(providerBeanName, providerBeanDef)

          // And then the provider bean gets used as the factory bean, calling its get method, for the actual bean
          beanDef.setFactoryBeanName(providerBeanName)
          beanDef.setFactoryMethodName("get")

        case Some(ProviderTarget(provider)) =>

          // We have an actual instance of that provider, we create a factory bean to wrap and invoke that provider instance
          beanDef.setBeanClass(classOf[ProviderFactoryBean[_]])
          val args = new ConstructorArgumentValues()
          args.addIndexedArgumentValue(0, provider)
          args.addIndexedArgumentValue(1, binding.key.clazz)
          args.addIndexedArgumentValue(2, beanFactory)
          beanDef.setConstructorArgumentValues(args)

        case Some(BindingKeyTarget(key)) =>

          // It's an alias, create a factory bean that will look up the alias
          beanDef.setBeanClass(classOf[BindingKeyFactoryBean[_]])
          val args = new ConstructorArgumentValues()
          args.addIndexedArgumentValue(0, key)
          args.addIndexedArgumentValue(1, binding.key.clazz)
          args.addIndexedArgumentValue(2, beanFactory)
          beanDef.setConstructorArgumentValues(args)
      }

      binding.scope match {
        case None =>
          // Do nothing, we've already defaulted or detected the scope
        case Some(scope) =>
          SpringApplicationLoader.setScope(beanDef, scope)
      }

      beanFactory.registerBeanDefinition(beanName, beanDef)
    }
  }

  /**
   * Turns an instance of an annotation into a spring qualifier descriptor.
   */
  private def qualifierFromInstance(instance: Annotation) = {
    val annotationType = instance.annotationType()
    val qualifier = new AutowireCandidateQualifier(annotationType)
    AnnotationUtils.getAnnotationAttributes(instance).asScala.foreach {
      case (attribute, value) => qualifier.setAttribute(attribute, value)
    }

    qualifier
  }

}

/**
 * Shared functionality
 */
private object SpringApplicationLoader {

  /**
   * Set the scope on the given bean definition if a scope annotation is declared on the class.
   */
  def maybeSetScope(bd: GenericBeanDefinition, clazz: Class[_]) {
    clazz.getAnnotations.foreach { annotation =>
      if (annotation.annotationType().getAnnotations.exists(_.annotationType() == classOf[javax.inject.Scope])) {
        setScope(bd, annotation.annotationType())
      }
    }
  }

  /**
   * Set the given scope annotation scope on the given bean definition.
   */
  def setScope(bd: GenericBeanDefinition, clazz: Class[_ <: Annotation]) = {
    clazz match {
      case singleton if singleton == classOf[javax.inject.Singleton] =>
        bd.setScope(BeanDefinition.SCOPE_SINGLETON)
      case other =>
      // todo: use Jsr330ScopeMetaDataResolver to resolve and set scope
    }
  }

}

/**
 * A factory bean that wraps a provider.
 */
class ProviderFactoryBean[T](provider: Provider[T], objectType: Class[_], factory: AutowireCapableBeanFactory)
  extends FactoryBean[T] {

  lazy val injectedProvider = {
    // Autowire the providers properties - Play needs this in a few places.
    val bpp = new AutowiredAnnotationBeanPostProcessor()
    bpp.setBeanFactory(factory)
    bpp.processInjection(provider)
    provider
  }

  def getObject = injectedProvider.get()

  def getObjectType = objectType

  def isSingleton = false
}

/**
 * A factory bean that wraps a binding key alias.
 */
class BindingKeyFactoryBean[T](key: BindingKey[T], objectType: Class[_], factory: DefaultListableBeanFactory) extends FactoryBean[T] {
  /**
   * The bean name, if it can be determined.
   *
   * Will either return a new bean name, or if the by type lookup should be done on request (in the case of an
   * unqualified lookup because it's cheaper to delegate that to Spring) then do it on request.  Will throw an
   * exception if a key for which no matching bean can be found is found.
   */
  lazy val beanName: Option[String] = {
    key.qualifier match {
      case None =>
        None
      case Some(QualifierClass(qualifier)) =>
        val candidates = factory.getBeanNamesForType(key.clazz)
        val matches = candidates.toList
          .map(name => new BeanDefinitionHolder(factory.getBeanDefinition(name), name))
          .filter { bdh =>
            bdh.getBeanDefinition match {
              case abd: AbstractBeanDefinition =>
                abd.hasQualifier(qualifier.getName)
              case _ => false
            }
          }.map(_.getBeanName)
        getNameFromMatches(matches)
      case Some(QualifierInstance(qualifier)) =>
        val candidates = factory.getBeanNamesForType(key.clazz)
        val matches = candidates.toList
          .map(name => new BeanDefinitionHolder(factory.getBeanDefinition(name), name))
          .filter( bdh => QualifierChecker.checkQualifier(bdh, qualifier, factory.getTypeConverter))
          .map(_.getBeanName)
        getNameFromMatches(matches)
    }
  }

  private def getNameFromMatches(candidates: Seq[String]): Option[String] = {
    candidates match {
      case Nil => throw new NoSuchBeanDefinitionException(key.clazz, "Binding alias for type " + objectType + " to " + key,
        "No bean found for binding alias")
      case single :: Nil => Some(single)
      case multiple => throw new NoUniqueBeanDefinitionException(key.clazz, multiple.asJava)
    }

  }

  def getObject = {
    beanName.fold(factory.getBean(key.clazz))(name => factory.getBean(name).asInstanceOf[T])
  }

  def getObjectType = objectType

  def isSingleton = false
}

/**
 * Hack to expose the checkQualifier method as public.
 */
object QualifierChecker extends QualifierAnnotationAutowireCandidateResolver {

  /**
   * Override to expose as public
   */
  override def checkQualifier(bdHolder: BeanDefinitionHolder, annotation: Annotation, typeConverter: TypeConverter) = {
    bdHolder.getBeanDefinition match {
      case root: RootBeanDefinition => super.checkQualifier(bdHolder, annotation, typeConverter)
      case nonRoot =>
        val bdh = new BeanDefinitionHolder(RootBeanDefinitionCreator.create(nonRoot), bdHolder.getBeanName)
        super.checkQualifier(bdh, annotation, typeConverter)
    }
  }
}

/**
 * A spring implementation of the injector.
 */
class SpringInjector(factory: DefaultListableBeanFactory) extends Injector {

  private val bpp = new AutowiredAnnotationBeanPostProcessor()
  bpp.setBeanFactory(factory)

  def instanceOf[T](implicit ct: ClassTag[T]) = instanceOf(ct.runtimeClass).asInstanceOf[T]

  def instanceOf[T](clazz: Class[T]) = {
    try {
      factory.getBean(clazz)
    } catch {
      case e: NoSuchBeanDefinitionException =>
        // if the class is a concrete type, attempt to create a just in time binding
        if (!clazz.isInterface /* todo check if abstract, how? */) {
          val beanDef = new GenericBeanDefinition()
          beanDef.setScope(BeanDefinition.SCOPE_PROTOTYPE)
          SpringApplicationLoader.maybeSetScope(beanDef, clazz)
          beanDef.setBeanClass(clazz)
          beanDef.setPrimary(true)
          beanDef.setAutowireMode(AutowireCapableBeanFactory.AUTOWIRE_AUTODETECT)
          factory.registerBeanDefinition(clazz.getName, beanDef)

          Play.logger.debug("Attempting just in time bean registration for bean with class " + clazz)

          val bean = factory.getBean(clazz)
          // todo - this ensures fields get injected, see if there's a way that this can be done automatically
          bpp.processInjection(bean)
          bean

        } else {
          throw e
        }
    }
  }

  def instanceOf[T](key: BindingKey[T]) = {
    if (key.qualifier.isEmpty) {
      instanceOf(key.clazz)
    } else {
      new BindingKeyFactoryBean[T](key, key.clazz, factory).getObject
    }
  }
}

