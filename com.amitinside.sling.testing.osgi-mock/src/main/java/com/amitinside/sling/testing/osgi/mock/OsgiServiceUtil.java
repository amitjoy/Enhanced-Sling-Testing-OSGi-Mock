/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.amitinside.sling.testing.osgi.mock;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.apache.commons.lang3.StringUtils;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;

import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.FieldCollectionType;
import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.OsgiMetadata;
import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.Reference;
import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.ReferencePolicy;

/**
 * Helper methods to inject dependencies and activate services.
 */
final class OsgiServiceUtil {

	static class ReferenceInfo {

		private final Reference reference;
		private final MockServiceRegistration serviceRegistration;

		public ReferenceInfo(final MockServiceRegistration serviceRegistration, final Reference reference) {
			this.serviceRegistration = serviceRegistration;
			this.reference = reference;
		}

		public Reference getReference() {
			return this.reference;
		}

		public MockServiceRegistration getServiceRegistration() {
			return this.serviceRegistration;
		}

	}

	static class ServiceInfo {

		private final Map<String, Object> serviceConfig;
		private final Object serviceInstance;
		private final ServiceReference serviceReference;

		@SuppressWarnings("unchecked")
		public ServiceInfo(final MockServiceRegistration registration) {
			this.serviceInstance = registration.getService();
			this.serviceConfig = MapUtil.toMap(registration.getProperties());
			this.serviceReference = registration.getReference();
		}

		public ServiceInfo(final Object serviceInstance, final Map<String, Object> serviceConfig,
				final ServiceReference serviceReference) {
			this.serviceInstance = serviceInstance;
			this.serviceConfig = serviceConfig;
			this.serviceReference = serviceReference;
		}

		public Map<String, Object> getServiceConfig() {
			return this.serviceConfig;
		}

		public Object getServiceInstance() {
			return this.serviceInstance;
		}

		public ServiceReference getServiceReference() {
			return this.serviceReference;
		}

	}

	/**
	 * Simulate activation or deactivation of OSGi service instance.
	 *
	 * @param target
	 *            Service instance.
	 * @param componentContext
	 *            Component context
	 * @return true if activation/deactivation method was called. False if it
	 *         failed.
	 */
	public static boolean activateDeactivate(final Object target, final ComponentContext componentContext,
			final boolean activate) {
		final Class<?> targetClass = target.getClass();

		// get method name for activation/deactivation from osgi metadata
		final OsgiMetadata metadata = OsgiMetadataUtil.getMetadata(targetClass);
		if (metadata == null) {
			throw new NoScrMetadataException(targetClass);
		}
		String methodName;
		if (activate) {
			methodName = metadata.getActivateMethodName();
		} else {
			methodName = metadata.getDeactivateMethodName();
		}
		boolean fallbackDefaultName = false;
		if (StringUtils.isEmpty(methodName)) {
			fallbackDefaultName = true;
			if (activate) {
				methodName = "activate";
			} else {
				methodName = "deactivate";
			}
		}

		// try to find matching activate/deactivate method and execute it
		if (invokeLifecycleMethod(target, targetClass, methodName, !activate, componentContext,
				MapUtil.toMap(componentContext.getProperties()))) {
			return true;
		}

		if (fallbackDefaultName) {
			return false;
		}

		throw new RuntimeException("No matching " + (activate ? "activation" : "deactivation") + " method with name '"
				+ methodName + "' " + " found in class " + targetClass.getName());
	}

	@SuppressWarnings("unchecked")
	private static void addToCollection(final Object target, final Field field, final Object item) {
		try {
			field.setAccessible(true);
			Collection<Object> collection = (Collection<Object>) field.get(target);
			if (collection == null) {
				collection = new ArrayList<Object>();
			}
			if (item != null) {
				collection.add(item);
			}
			field.set(target, collection);

		} catch (final IllegalAccessException ex) {
			throw new RuntimeException(
					"Unable to set field '" + field.getName() + "' for class " + target.getClass().getName(), ex);
		} catch (final IllegalArgumentException ex) {
			throw new RuntimeException(
					"Unable to set field '" + field.getName() + "' for class " + target.getClass().getName(), ex);
		}
	}

	private static Field getField(final Class clazz, final String fieldName, final Class<?> type) {
		final Field[] fields = clazz.getDeclaredFields();
		for (final Field field : fields) {
			if (StringUtils.equals(field.getName(), fieldName) && field.getType().equals(type)) {
				return field;
			}
		}
		// not found? check super classes
		final Class<?> superClass = clazz.getSuperclass();
		if ((superClass != null) && (superClass != Object.class)) {
			return getField(superClass, fieldName, type);
		}
		return null;
	}

	private static Field getFieldWithAssignableType(final Class clazz, final String fieldName, final Class<?> type) {
		final Field[] fields = clazz.getDeclaredFields();
		for (final Field field : fields) {
			if (StringUtils.equals(field.getName(), fieldName) && field.getType().isAssignableFrom(type)) {
				return field;
			}
		}
		// not found? check super classes
		final Class<?> superClass = clazz.getSuperclass();
		if ((superClass != null) && (superClass != Object.class)) {
			return getFieldWithAssignableType(superClass, fieldName, type);
		}
		return null;
	}

	/**
	 * Collects all references of any registered service that match with any of
	 * the exported interfaces of the given service registration.
	 *
	 * @param registeredServices
	 *            Registered Services
	 * @param registration
	 *            Service registration
	 * @return List of references
	 */
	public static List<ReferenceInfo> getMatchingDynamicReferences(
			final SortedSet<MockServiceRegistration> registeredServices,
			final MockServiceRegistration<?> registration) {
		final List<ReferenceInfo> references = new ArrayList<ReferenceInfo>();
		for (final MockServiceRegistration existingRegistration : registeredServices) {
			final OsgiMetadata metadata = OsgiMetadataUtil.getMetadata(existingRegistration.getService().getClass());
			if (metadata != null) {
				for (final Reference reference : metadata.getReferences()) {
					if (reference.getPolicy() == ReferencePolicy.DYNAMIC) {
						for (final String serviceInterface : registration.getClasses()) {
							if (StringUtils.equals(serviceInterface, reference.getInterfaceType())) {
								references.add(new ReferenceInfo(existingRegistration, reference));
							}
						}
					}
				}
			}
		}
		return references;
	}

	private static List<ServiceInfo> getMatchingServices(final Class<?> type, final BundleContext bundleContext,
			final String filter) {
		final List<ServiceInfo> matchingServices = new ArrayList<ServiceInfo>();
		try {
			final ServiceReference[] references = bundleContext.getServiceReferences(type.getName(), filter);
			if (references != null) {
				for (final ServiceReference<?> serviceReference : references) {
					final Object serviceInstance = bundleContext.getService(serviceReference);
					final Map<String, Object> serviceConfig = new HashMap<String, Object>();
					final String[] keys = serviceReference.getPropertyKeys();
					for (final String key : keys) {
						serviceConfig.put(key, serviceReference.getProperty(key));
					}
					matchingServices.add(new ServiceInfo(serviceInstance, serviceConfig, serviceReference));
				}
			}
		} catch (final InvalidSyntaxException ex) {
			// ignore
		}
		return matchingServices;
	}

	private static Method getMethod(final Class clazz, final String methodName, final Class<?>[] types) {
		final Method[] methods = clazz.getDeclaredMethods();
		for (final Method method : methods) {
			if (StringUtils.equals(method.getName(), methodName)
					&& (method.getParameterTypes().length == types.length)) {
				boolean foundMismatch = false;
				for (int i = 0; i < types.length; i++) {
					if (!((method.getParameterTypes()[i] == types[i])
							|| ((types[i] == Annotation.class) && method.getParameterTypes()[i].isAnnotation()))) {
						foundMismatch = true;
						break;
					}
				}
				if (!foundMismatch) {
					return method;
				}
			}
		}
		// not found? check super classes
		final Class<?> superClass = clazz.getSuperclass();
		if ((superClass != null) && (superClass != Object.class)) {
			return getMethod(superClass, methodName, types);
		}
		return null;
	}

	private static Method getMethodWithAnyCombinationArgs(final Class clazz, final String methodName,
			final Class<?>[] types) {
		final Method[] methods = clazz.getDeclaredMethods();
		for (final Method method : methods) {
			if (StringUtils.equals(method.getName(), methodName) && (method.getParameterTypes().length > 1)) {
				boolean foundMismatch = false;
				for (final Class<?> parameterType : method.getParameterTypes()) {
					boolean foundAnyMatch = false;
					for (final Class<?> type : types) {
						if ((parameterType == type) || ((type == Annotation.class) && parameterType.isAnnotation())) {
							foundAnyMatch = true;
							break;
						}
					}
					if (!foundAnyMatch) {
						foundMismatch = true;
						break;
					}
				}
				if (!foundMismatch) {
					return method;
				}
			}
		}
		// not found? check super classes
		final Class<?> superClass = clazz.getSuperclass();
		if ((superClass != null) && (superClass != Object.class)) {
			return getMethodWithAnyCombinationArgs(superClass, methodName, types);
		}
		return null;
	}

	private static Method getMethodWithAssignableTypes(final Class clazz, final String methodName,
			final Class<?>[] types) {
		final Method[] methods = clazz.getDeclaredMethods();
		for (final Method method : methods) {
			if (StringUtils.equals(method.getName(), methodName)
					&& (method.getParameterTypes().length == types.length)) {
				boolean foundMismatch = false;
				for (int i = 0; i < types.length; i++) {
					if (!method.getParameterTypes()[i].isAssignableFrom(types[i])) {
						foundMismatch = true;
						break;
					}
				}
				if (!foundMismatch) {
					return method;
				}
			}
		}
		// not found? check super classes
		final Class<?> superClass = clazz.getSuperclass();
		if ((superClass != null) && (superClass != Object.class)) {
			return getMethodWithAssignableTypes(superClass, methodName, types);
		}
		return null;
	}

	private static void injectServiceReference(final Reference reference, final Object target,
			final BundleContext bundleContext) {
		final Class<?> targetClass = target.getClass();

		// get reference type
		final Class<?> type = reference.getInterfaceTypeAsClass();

		// get matching service references
		final List<ServiceInfo> matchingServices = getMatchingServices(type, bundleContext, reference.getTarget());

		// no references found? check if reference was optional
		if (matchingServices.isEmpty()) {
			if (!reference.isCardinalityOptional()) {
				throw new ReferenceViolationException("Unable to inject mandatory reference '" + reference.getName()
						+ "' for class " + targetClass.getName() + " : no matching services were found.");
			}
			if (reference.isCardinalityMultiple()) {
				// make sure at least empty array is set
				invokeBindUnbindMethod(reference, target, null, true);
			}
		}

		// multiple references found? check if reference is not multiple
		if ((matchingServices.size() > 1) && !reference.isCardinalityMultiple()) {
			throw new ReferenceViolationException("Multiple matches found for unary reference '" + reference.getName()
					+ "' for class " + targetClass.getName());
		}

		// try to invoke bind method
		for (final ServiceInfo matchingService : matchingServices) {
			invokeBindUnbindMethod(reference, target, matchingService, true);
		}
	}

	/**
	 * Simulate OSGi service dependency injection. Injects direct references and
	 * multiple references.
	 *
	 * @param target
	 *            Service instance
	 * @param bundleContext
	 *            Bundle context from which services are fetched to inject.
	 * @return true if all dependencies could be injected, false if the service
	 *         has no dependencies.
	 */
	public static boolean injectServices(final Object target, final BundleContext bundleContext) {

		// collect all declared reference annotations on class and field level
		final Class<?> targetClass = target.getClass();

		final OsgiMetadata metadata = OsgiMetadataUtil.getMetadata(targetClass);
		if (metadata == null) {
			throw new NoScrMetadataException(targetClass);
		}
		final List<Reference> references = metadata.getReferences();
		if (references.isEmpty()) {
			return false;
		}

		// try to inject services
		for (final Reference reference : references) {
			injectServiceReference(reference, target, bundleContext);
		}
		return true;
	}

	/**
	 * Directly invoke bind method on service for the given reference.
	 *
	 * @param reference
	 *            Reference metadata
	 * @param target
	 *            Target object for reference
	 * @param serviceInfo
	 *            Service on which to invoke the method
	 */
	public static void invokeBindMethod(final Reference reference, final Object target, final ServiceInfo serviceInfo) {
		invokeBindUnbindMethod(reference, target, serviceInfo, true);
	}

	private static void invokeBindUnbindMethod(final Reference reference, final Object target,
			final ServiceInfo serviceInfo, final boolean bind) {
		final Class<?> targetClass = target.getClass();

		// try to invoke bind method
		final String methodName = bind ? reference.getBind() : reference.getUnbind();
		final String fieldName = reference.getField();

		if (StringUtils.isEmpty(methodName) && StringUtils.isEmpty(fieldName)) {
			throw new RuntimeException("No bind/unbind method name or file name defined " + "for reference '"
					+ reference.getName() + "' for class " + targetClass.getName());
		}

		if (StringUtils.isNotEmpty(methodName) && (serviceInfo != null)) {

			// 1. ServiceReference
			Method method = getMethod(targetClass, methodName, new Class<?>[] { ServiceReference.class });
			if (method != null) {
				invokeMethod(target, method, new Object[] { serviceInfo.getServiceReference() });
				return;
			}

			// 2. assignable from service instance
			final Class<?> interfaceType = reference.getInterfaceTypeAsClass();
			method = getMethodWithAssignableTypes(targetClass, methodName, new Class<?>[] { interfaceType });
			if (method != null) {
				invokeMethod(target, method, new Object[] { serviceInfo.getServiceInstance() });
				return;
			}

			// 3. assignable from service instance plus map
			method = getMethodWithAssignableTypes(targetClass, methodName, new Class<?>[] { interfaceType, Map.class });
			if (method != null) {
				invokeMethod(target, method,
						new Object[] { serviceInfo.getServiceInstance(), serviceInfo.getServiceConfig() });
				return;
			}

			throw new RuntimeException((bind ? "Bind" : "Unbind") + " method with name " + methodName + " not found "
					+ "for reference '" + reference.getName() + "' for class " + targetClass.getName());
		}

		// in OSGi declarative services 1.3 there are no bind/unbind methods -
		// modify the field directly
		else if (StringUtils.isNotEmpty(fieldName)) {

			// check for field with list/collection reference
			if (reference.isCardinalityMultiple()) {
				switch (reference.getFieldCollectionType()) {
				case SERVICE:
				case REFERENCE:
					Object item = null;
					if (serviceInfo != null) {
						item = serviceInfo.getServiceInstance();
						if (reference.getFieldCollectionType() == FieldCollectionType.REFERENCE) {
							item = serviceInfo.getServiceReference();
						}
					}
					// 1. collection
					Field field = getFieldWithAssignableType(targetClass, fieldName, Collection.class);
					if (field != null) {
						if (bind) {
							addToCollection(target, field, item);
						} else {
							removeFromCollection(target, field, item);
						}
						return;
					}

					// 2. list
					field = getField(targetClass, fieldName, List.class);
					if (field != null) {
						if (bind) {
							addToCollection(target, field, item);
						} else {
							removeFromCollection(target, field, item);
						}
						return;
					}
					break;
				default:
					throw new RuntimeException(
							"Field collection type '" + reference.getFieldCollectionType() + "' not supported "
									+ "for reference '" + reference.getName() + "' for class " + targetClass.getName());
				}
			}

			// check for single field reference
			else {
				// 1. assignable from service instance
				final Class<?> interfaceType = reference.getInterfaceTypeAsClass();
				Field field = getFieldWithAssignableType(targetClass, fieldName, interfaceType);
				if (field != null) {
					setField(target, field, bind && (serviceInfo != null) ? serviceInfo.getServiceInstance() : null);
					return;
				}

				// 2. ServiceReference
				field = getField(targetClass, fieldName, ServiceReference.class);
				if (field != null) {
					setField(target, field, bind && (serviceInfo != null) ? serviceInfo.getServiceReference() : null);
					return;
				}
			}
		}

	}

	/**
	 * Invokes a lifecycle method (activation, deactivation or modified) with
	 * variable method arguments.
	 *
	 * @param target
	 *            Target object
	 * @param targetClass
	 *            Target object class
	 * @param methodName
	 *            Method name
	 * @param allowIntegerArgument
	 *            Allow int or Integer as arguments (only decactivate)
	 * @param componentContext
	 *            Component context
	 * @param properties
	 *            Component properties
	 * @return true if a method was found and invoked
	 */
	private static boolean invokeLifecycleMethod(final Object target, final Class<?> targetClass,
			final String methodName, final boolean allowIntegerArgument, final ComponentContext componentContext,
			final Map<String, Object> properties) {

		// 1. componentContext
		Method method = getMethod(targetClass, methodName, new Class<?>[] { ComponentContext.class });
		if (method != null) {
			invokeMethod(target, method, new Object[] { componentContext });
			return true;
		}

		// 2. bundleContext
		method = getMethod(targetClass, methodName, new Class<?>[] { BundleContext.class });
		if (method != null) {
			invokeMethod(target, method, new Object[] { componentContext.getBundleContext() });
			return true;
		}

		// 3. map
		method = getMethod(targetClass, methodName, new Class<?>[] { Map.class });
		if (method != null) {
			invokeMethod(target, method, new Object[] { MapUtil.toMap(componentContext.getProperties()) });
			return true;
		}

		// 4. Component property type (annotation lass)
		// method = getMethod(targetClass, methodName, new Class<?>[] {
		// Annotation.class });
		// if (method != null) {
		// invokeMethod(target, method,
		// new Object[] { Annotations.toObject(method.getParameterTypes()[0],
		// MapUtil.toMap(componentContext.getProperties()),
		// componentContext.getBundleContext().getBundle(), false) });
		// return true;
		// }
		// TODO (AKM)
		// 5. int (deactivation only)
		if (allowIntegerArgument) {
			method = getMethod(targetClass, methodName, new Class<?>[] { int.class });
			if (method != null) {
				invokeMethod(target, method, new Object[] { 0 });
				return true;
			}
		}

		// 6. Integer (deactivation only)
		if (allowIntegerArgument) {
			method = getMethod(targetClass, methodName, new Class<?>[] { Integer.class });
			if (method != null) {
				invokeMethod(target, method, new Object[] { 0 });
				return true;
			}
		}

		// 7. mixed arguments
		final Class<?>[] mixedArgsAllowed = allowIntegerArgument
				? new Class<?>[] { ComponentContext.class, BundleContext.class, Map.class, Annotation.class, int.class,
						Integer.class }
				: new Class<?>[] { ComponentContext.class, BundleContext.class, Map.class, Annotation.class };
		method = getMethodWithAnyCombinationArgs(targetClass, methodName, mixedArgsAllowed);
		if (method != null) {
			final Object[] args = new Object[method.getParameterTypes().length];
			for (int i = 0; i < args.length; i++) {
				if (method.getParameterTypes()[i] == ComponentContext.class) {
					args[i] = componentContext;
				} else if (method.getParameterTypes()[i] == BundleContext.class) {
					args[i] = componentContext.getBundleContext();
				} else if (method.getParameterTypes()[i] == Map.class) {
					args[i] = MapUtil.toMap(componentContext.getProperties());
				} else if (method.getParameterTypes()[i].isAnnotation()) {
					// args[i] =
					// Annotations.toObject(method.getParameterTypes()[i],
					// MapUtil.toMap(componentContext.getProperties()),
					// componentContext.getBundleContext().getBundle(), false);
					// TODO (AKM)
				} else if ((method.getParameterTypes()[i] == int.class)
						|| (method.getParameterTypes()[i] == Integer.class)) {
					args[i] = 0;
				}
			}
			invokeMethod(target, method, args);
			return true;
		}

		// 8. noargs
		method = getMethod(targetClass, methodName, new Class<?>[0]);
		if (method != null) {
			invokeMethod(target, method, new Object[0]);
			return true;
		}

		return false;
	}

	private static void invokeMethod(final Object target, final Method method, final Object[] args) {
		try {
			method.setAccessible(true);
			method.invoke(target, args);
		} catch (final IllegalAccessException ex) {
			throw new RuntimeException(
					"Unable to invoke method '" + method.getName() + "' for class " + target.getClass().getName(), ex);
		} catch (final IllegalArgumentException ex) {
			throw new RuntimeException(
					"Unable to invoke method '" + method.getName() + "' for class " + target.getClass().getName(), ex);
		} catch (final InvocationTargetException ex) {
			throw new RuntimeException(
					"Unable to invoke method '" + method.getName() + "' for class " + target.getClass().getName(),
					ex.getCause());
		}
	}

	/**
	 * Directly invoke unbind method on service for the given reference.
	 *
	 * @param reference
	 *            Reference metadata
	 * @param target
	 *            Target object for reference
	 * @param serviceInfo
	 *            Service on which to invoke the method
	 */
	public static void invokeUnbindMethod(final Reference reference, final Object target,
			final ServiceInfo serviceInfo) {
		invokeBindUnbindMethod(reference, target, serviceInfo, false);
	}

	/**
	 * Simulate modification of configuration of OSGi service instance.
	 *
	 * @param target
	 *            Service instance.
	 * @param properties
	 *            Updated configuration
	 * @return true if modified method was called. False if it failed.
	 */
	public static boolean modified(final Object target, final ComponentContext componentContext,
			final Map<String, Object> properties) {
		final Class<?> targetClass = target.getClass();

		// get method name for activation/deactivation from osgi metadata
		final OsgiMetadata metadata = OsgiMetadataUtil.getMetadata(targetClass);
		if (metadata == null) {
			throw new NoScrMetadataException(targetClass);
		}
		final String methodName = metadata.getModifiedMethodName();
		if (StringUtils.isEmpty(methodName)) {
			return false;
		}

		// try to find matching modified method and execute it
		if (invokeLifecycleMethod(target, targetClass, methodName, false, componentContext, properties)) {
			return true;
		}

		throw new RuntimeException("No matching modified method with name '" + methodName + "' " + " found in class "
				+ targetClass.getName());
	}

	@SuppressWarnings("unchecked")
	private static void removeFromCollection(final Object target, final Field field, final Object item) {
		try {
			field.setAccessible(true);
			Collection<Object> collection = (Collection<Object>) field.get(target);
			if (collection == null) {
				collection = new ArrayList<Object>();
			}
			if (item != null) {
				collection.remove(item);
			}
			field.set(target, collection);

		} catch (final IllegalAccessException ex) {
			throw new RuntimeException(
					"Unable to set field '" + field.getName() + "' for class " + target.getClass().getName(), ex);
		} catch (final IllegalArgumentException ex) {
			throw new RuntimeException(
					"Unable to set field '" + field.getName() + "' for class " + target.getClass().getName(), ex);
		}
	}

	private static void setField(final Object target, final Field field, final Object value) {
		try {
			field.setAccessible(true);
			field.set(target, value);
		} catch (final IllegalAccessException ex) {
			throw new RuntimeException(
					"Unable to set field '" + field.getName() + "' for class " + target.getClass().getName(), ex);
		} catch (final IllegalArgumentException ex) {
			throw new RuntimeException(
					"Unable to set field '" + field.getName() + "' for class " + target.getClass().getName(), ex);
		}
	}

	private OsgiServiceUtil() {
		// static methods only
	}

}
