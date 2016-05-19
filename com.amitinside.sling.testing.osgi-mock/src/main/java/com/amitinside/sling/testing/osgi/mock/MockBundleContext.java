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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;

import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.Reference;
import com.amitinside.sling.testing.osgi.mock.OsgiServiceUtil.ReferenceInfo;
import com.amitinside.sling.testing.osgi.mock.OsgiServiceUtil.ServiceInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

/**
 * Mock {@link BundleContext} implementation.
 */
class MockBundleContext implements BundleContext {

	private final MockBundle bundle;
	private final Queue<BundleListener> bundleListeners = new ConcurrentLinkedQueue<BundleListener>();
	private final ConfigurationAdmin configAdmin = new MockConfigurationAdmin();
	private File dataFileBaseDir;
	private final SortedSet<MockServiceRegistration> registeredServices = new ConcurrentSkipListSet<MockServiceRegistration>();
	private final Map<ServiceListener, Filter> serviceListeners = new ConcurrentHashMap<ServiceListener, Filter>();

	public MockBundleContext() {
		this.bundle = new MockBundle(this);

		// register configuration admin by default
		this.registerService(ConfigurationAdmin.class.getName(), this.configAdmin, null);
	}

	@Override
	public void addBundleListener(final BundleListener bundleListener) {
		if (!this.bundleListeners.contains(bundleListener)) {
			this.bundleListeners.add(bundleListener);
		}
	}

	@Override
	public void addFrameworkListener(final FrameworkListener frameworkListener) {
		// accept method, but ignore it
	}

	@Override
	public void addServiceListener(final ServiceListener serviceListener) {
		try {
			this.addServiceListener(serviceListener, null);
		} catch (final InvalidSyntaxException ex) {
			throw new RuntimeException(ex);
		}
	}

	@Override
	public void addServiceListener(final ServiceListener serviceListener, final String filter)
			throws InvalidSyntaxException {
		this.serviceListeners.put(serviceListener, this.createFilter(filter));
	}

	@Override
	public Filter createFilter(final String s) throws InvalidSyntaxException {
		if (s == null) {
			return new MatchAllFilter();
		} else {
			return FilterImpl.newInstance(s);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public ServiceReference[] getAllServiceReferences(final String clazz, final String filter)
			throws InvalidSyntaxException {
		// for now just do the same as getServiceReferences
		return this.getServiceReferences(clazz, filter);
	}

	@Override
	public Bundle getBundle() {
		return this.bundle;
	}

	@Override
	public Bundle getBundle(final long l) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bundle getBundle(final String location) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bundle[] getBundles() {
		return new Bundle[0];
	}

	@Override
	public File getDataFile(final String path) {
		if (path == null) {
			throw new IllegalArgumentException("Invalid path: " + path);
		}
		synchronized (this) {
			if (this.dataFileBaseDir == null) {
				this.dataFileBaseDir = Files.createTempDir();
			}
		}
		if (path.isEmpty()) {
			return this.dataFileBaseDir;
		} else {
			return new File(this.dataFileBaseDir, path);
		}
	}

	@Override
	public String getProperty(final String s) {
		// no mock implementation, simulate that no property is found and return
		// null
		return null;
	}

	@Override
	public <S> S getService(final ServiceReference<S> serviceReference) {
		return ((MockServiceReference<S>) serviceReference).getService();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <S> ServiceReference<S> getServiceReference(final Class<S> clazz) {
		return this.getServiceReference(clazz.getName());
	}

	@SuppressWarnings("unchecked")
	@Override
	public ServiceReference getServiceReference(final String clazz) {
		try {
			final ServiceReference[] serviceRefs = this.getServiceReferences(clazz, null);
			if ((serviceRefs != null) && (serviceRefs.length > 0)) {
				return serviceRefs[0];
			}
		} catch (final InvalidSyntaxException ex) {
			// should not happen
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <S> Collection<ServiceReference<S>> getServiceReferences(final Class<S> clazz, final String filter)
			throws InvalidSyntaxException {
		final ServiceReference<S>[] result = this.getServiceReferences(clazz.getName(), filter);
		if (result == null) {
			return ImmutableList.<ServiceReference<S>>of();
		} else {
			return ImmutableList.<ServiceReference<S>>copyOf(result);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public ServiceReference[] getServiceReferences(final String clazz, final String filter)
			throws InvalidSyntaxException {
		final Set<ServiceReference> result = new TreeSet<ServiceReference>();
		for (final MockServiceRegistration serviceRegistration : this.registeredServices) {
			if (serviceRegistration.matches(clazz, filter)) {
				result.add(serviceRegistration.getReference());
			}
		}
		if (result.isEmpty()) {
			return null;
		} else {
			return result.toArray(new ServiceReference[result.size()]);
		}
	}

	/**
	 * Check for already registered services that may be affected by the service
	 * registration - either adding by additional optional references, or
	 * creating a conflict in the dependencies.
	 *
	 * @param registration
	 */
	private void handleRefsUpdateOnRegister(final MockServiceRegistration registration) {
		final List<ReferenceInfo> affectedReferences = OsgiServiceUtil
				.getMatchingDynamicReferences(this.registeredServices, registration);
		for (final ReferenceInfo referenceInfo : affectedReferences) {
			final Reference reference = referenceInfo.getReference();
			if (reference.matchesTargetFilter(registration.getReference())) {
				switch (reference.getCardinality()) {
				case MANDATORY_UNARY:
					throw new ReferenceViolationException(
							"Mandatory unary reference of type " + reference.getInterfaceType() + " already fulfilled "
									+ "for service " + reference.getServiceClass().getName()
									+ ", registration of new service with this interface failed.");
				case MANDATORY_MULTIPLE:
				case OPTIONAL_MULTIPLE:
				case OPTIONAL_UNARY:
					OsgiServiceUtil.invokeBindMethod(reference, referenceInfo.getServiceRegistration().getService(),
							new ServiceInfo(registration));
					break;
				default:
					throw new RuntimeException("Unepxected cardinality: " + reference.getCardinality());
				}
			}
		}
	}

	/**
	 * Check for already registered services that may be affected by the service
	 * unregistration - either adding by removing optional references, or
	 * creating a conflict in the dependencies.
	 *
	 * @param registration
	 */
	private void handleRefsUpdateOnUnregister(final MockServiceRegistration registration) {
		final List<ReferenceInfo> affectedReferences = OsgiServiceUtil
				.getMatchingDynamicReferences(this.registeredServices, registration);
		for (final ReferenceInfo referenceInfo : affectedReferences) {
			final Reference reference = referenceInfo.getReference();
			if (reference.matchesTargetFilter(registration.getReference())) {
				switch (reference.getCardinality()) {
				case MANDATORY_UNARY:
					throw new ReferenceViolationException("Reference of type " + reference.getInterfaceType() + " "
							+ "for service " + reference.getServiceClass().getName() + " is mandatory unary, "
							+ "unregistration of service with this interface failed.");
				case MANDATORY_MULTIPLE:
				case OPTIONAL_MULTIPLE:
				case OPTIONAL_UNARY:
					// it is currently not checked if for a MANDATORY_MULTIPLE
					// reference the last reference is removed
					OsgiServiceUtil.invokeUnbindMethod(reference, referenceInfo.getServiceRegistration().getService(),
							new ServiceInfo(registration));
					break;
				default:
					throw new RuntimeException("Unepxected cardinality: " + reference.getCardinality());
				}
			}
		}
	}

	// --- unsupported operations ---
	@Override
	public Bundle installBundle(final String s) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bundle installBundle(final String s, final InputStream inputStream) {
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("unchecked")
	<S> S locateService(final String name, final ServiceReference<S> reference) {
		for (final MockServiceRegistration<?> serviceRegistration : this.registeredServices) {
			if (serviceRegistration.getReference() == reference) {
				return (S) serviceRegistration.getService();
			}
		}
		return null;
	}

	private void notifyServiceListeners(final int eventType, final ServiceReference serviceReference) {
		final ServiceEvent event = new ServiceEvent(eventType, serviceReference);
		for (final Map.Entry<ServiceListener, Filter> entry : this.serviceListeners.entrySet()) {
			if ((entry.getValue() == null) || entry.getValue().match(serviceReference)) {
				entry.getKey().serviceChanged(event);
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <S> ServiceRegistration<S> registerService(final Class<S> clazz, final S service,
			final Dictionary<String, ?> properties) {
		return this.registerService(clazz.getName(), service, properties);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ServiceRegistration registerService(final String clazz, final Object service, final Dictionary properties) {
		String[] clazzes;
		if (StringUtils.isBlank(clazz)) {
			clazzes = new String[0];
		} else {
			clazzes = new String[] { clazz };
		}
		return this.registerService(clazzes, service, properties);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ServiceRegistration registerService(final String[] clazzes, final Object service,
			final Dictionary properties) {
		final Dictionary<String, Object> mergedPropertes = MapUtil.propertiesMergeWithOsgiMetadata(service,
				this.configAdmin, properties);
		final MockServiceRegistration registration = new MockServiceRegistration(this.bundle, clazzes, service,
				mergedPropertes, this);
		this.handleRefsUpdateOnRegister(registration);
		this.registeredServices.add(registration);
		this.notifyServiceListeners(ServiceEvent.REGISTERED, registration.getReference());
		return registration;
	}

	@Override
	public void removeBundleListener(final BundleListener bundleListener) {
		this.bundleListeners.remove(bundleListener);
	}

	@Override
	public void removeFrameworkListener(final FrameworkListener frameworkListener) {
		// accept method, but ignore it
	}

	@Override
	public void removeServiceListener(final ServiceListener serviceListener) {
		this.serviceListeners.remove(serviceListener);
	}

	void sendBundleEvent(final BundleEvent bundleEvent) {
		for (final BundleListener bundleListener : this.bundleListeners) {
			bundleListener.bundleChanged(bundleEvent);
		}
	}

	/**
	 * Deactivates all bundles registered in this mocked bundle context.
	 */
	public void shutdown() {
		for (final MockServiceRegistration<?> serviceRegistration : ImmutableList.copyOf(this.registeredServices)
				.reverse()) {
			try {
				MockOsgi.deactivate(serviceRegistration.getService(), this, serviceRegistration.getProperties());
			} catch (final NoScrMetadataException ex) {
				// ignore, no deactivate method is available then
			}
		}
		if (this.dataFileBaseDir != null) {
			try {
				FileUtils.deleteDirectory(this.dataFileBaseDir);
			} catch (final IOException e) {
				// ignore
			}
		}
	}

	@Override
	public boolean ungetService(final ServiceReference serviceReference) {
		// do nothing for now
		return false;
	}

	void unregisterService(final MockServiceRegistration registration) {
		this.registeredServices.remove(registration);
		this.handleRefsUpdateOnUnregister(registration);
		this.notifyServiceListeners(ServiceEvent.UNREGISTERING, registration.getReference());
	}

}
