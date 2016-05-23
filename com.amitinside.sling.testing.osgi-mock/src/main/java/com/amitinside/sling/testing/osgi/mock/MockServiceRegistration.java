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

import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.OsgiMetadata;
import com.google.common.collect.ImmutableList;

/**
 * Mock {@link ServiceRegistration} implementation.
 */
class MockServiceRegistration<T> implements ServiceRegistration, Comparable<MockServiceRegistration<T>> {

	private static volatile long serviceCounter;

	private final MockBundleContext bundleContext;
	private final Set<String> clazzes;
	private Dictionary<String, Object> properties;
	private final T service;
	private final Long serviceId;
	private final ServiceReference serviceReference;

	@SuppressWarnings("unchecked")
	public MockServiceRegistration(final Bundle bundle, final String[] clazzes, final T service,
			final Dictionary<String, Object> properties, final MockBundleContext bundleContext) {
		this.serviceId = ++serviceCounter;
		this.clazzes = new HashSet<String>(ImmutableList.copyOf(clazzes));

		if (service instanceof ServiceFactory) {
			this.service = (T) ((ServiceFactory) service).getService(bundleContext.getBundle(), this);
		} else {
			this.service = service;
		}

		this.properties = properties != null ? properties : new Hashtable<String, Object>();
		this.properties.put(Constants.SERVICE_ID, this.serviceId);
		this.properties.put(Constants.OBJECTCLASS, clazzes);
		this.serviceReference = new MockServiceReference<T>(bundle, this);
		this.bundleContext = bundleContext;

		this.readOsgiMetadata();
	}

	@Override
	public int compareTo(final MockServiceRegistration<T> obj) {
		return this.serviceId.compareTo(obj.serviceId);
	}

	@Override
	public boolean equals(final Object obj) {
		if (!(obj instanceof MockServiceRegistration)) {
			return false;
		}
		return this.serviceId.equals(((MockServiceRegistration) obj).serviceId);
	}

	Set<String> getClasses() {
		return this.clazzes;
	}

	Dictionary<String, Object> getProperties() {
		return this.properties;
	}

	@Override
	public ServiceReference getReference() {
		return this.serviceReference;
	}

	T getService() {
		return this.service;
	}

	@Override
	public int hashCode() {
		return this.serviceId.hashCode();
	}

	boolean matches(final String clazz, final String filter) throws InvalidSyntaxException {
		// ignore filter for now
		return this.clazzes.contains(clazz)
				&& ((filter == null) || FilterImpl.newInstance(filter).match(this.properties));
	}

	/**
	 * Try to read OSGI-metadata from /OSGI-INF and read all implemented
	 * interfaces
	 */
	private void readOsgiMetadata() {
		final Class<?> serviceClass = this.service.getClass();
		final OsgiMetadata metadata = OsgiMetadataUtil.getMetadata(serviceClass);
		if (metadata == null) {
			return;
		}

		// add service interfaces from OSGi metadata
		this.clazzes.addAll(metadata.getServiceInterfaces());
	}

	@SuppressWarnings("unchecked")
	@Override
	public void setProperties(final Dictionary properties) {
		this.properties = properties;
	}

	@Override
	public String toString() {
		return "#" + this.serviceId + " [" + StringUtil.join(this.clazzes, ",") + "]: " + this.service.toString();
	}

	@Override
	public void unregister() {
		this.bundleContext.unregisterService(this);
	}

}
