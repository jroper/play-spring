# Play 2.4 Spring DI support

This is a proof of concept to validate the abstraction created in Play 2.4 for runtime dependency injection, ensuring that it can be implemented by Spring.

## Current status

I have run the Play test suite, substituting this in instead of Guice, and it works.

## TODO

* Provide a mechanism for users to provide their own beans (annotation/classpath based, xml based, etc)
* Clean up code to use more spring mechanisms, such as implement a custom bean definition reader, instead of defining beans directly.
* Generate better names for built in beans, using Spring bean name generators.
* Proper JSR330 custom scope support.
* Work out why, and fix if possible, AutowireCapableBeanFactory.autowireBeanProperties isn't able to do field injection, only setter injection.
