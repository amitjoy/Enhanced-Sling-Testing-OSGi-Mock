# Enhanced-Sling-Testing-OSGi-Mock

This is an enhanced version of Apache Sling Testing OSGi Mock utility to provide mock implementations of OSGi API for easier testing. The actual utility library is very tightly coupled to Apache Sling and Apache Felix libraries.

That is why, this project has been started to remove all these dependencies so that this can be available as a standalone bundle.

Features:

1. Added OSGi R4 Support
2. Removed Apache Felix Support
2. Added Equinox Support
3. Added Google Guava Dependency
4. Removed Apache Commons Dependency
5. Eclipse Kura Development Environment Compatible (Tested)

### Maven Central
```xml
<dependency>
    <groupId>com.amitinside</groupId>
    <artifactId>com.amitinside.sling.testing.osgi-mock</artifactId>
    <version>1.2.0</version>
</dependency>
```

