package org.springframework.beans.factory.support

import org.springframework.beans.factory.config.BeanDefinition

/**
 * This class is a hack (defined in the spring package to access the package private constructor)
 *
 * If we were to subclass the bean factory, it wouldn't be needed, as it provides protected methods that could be used.
 */
object RootBeanDefinitionCreator {

  def create(bd: BeanDefinition): RootBeanDefinition = new RootBeanDefinition(bd)
}
