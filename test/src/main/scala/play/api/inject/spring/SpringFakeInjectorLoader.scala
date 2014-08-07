package play.api.inject.spring

import play.api.inject.Injector
import play.api.{Configuration, Environment}
import play.api.test.FakeInjectorLoader

class SpringFakeInjectorLoader extends FakeInjectorLoader {
  def createInjector(environment: Environment, configuration: Configuration, modules: Seq[Any]) = {
    new SpringApplicationLoader().createApplicationContext(environment, configuration, modules).getBean(classOf[Injector])
  }
}
