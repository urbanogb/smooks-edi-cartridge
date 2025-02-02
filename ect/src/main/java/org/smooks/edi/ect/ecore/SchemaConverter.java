/*-
 * ========================LICENSE_START=================================
 * smooks-ect
 * %%
 * Copyright (C) 2020 Smooks
 * %%
 * Licensed under the terms of the Apache License Version 2.0, or
 * the GNU Lesser General Public License version 3.0 or later.
 * 
 * SPDX-License-Identifier: Apache-2.0 OR LGPL-3.0-or-later
 * 
 * ======================================================================
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * ======================================================================
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * =========================LICENSE_END==================================
 */
package org.smooks.edi.ect.ecore;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.ExtendedMetaData;
import org.eclipse.xsd.XSDSchema;
import org.eclipse.xsd.util.XSDResourceFactoryImpl;
import org.smooks.edi.edisax.archive.Archive;
import org.smooks.edi.edisax.model.internal.Edimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Set;

public class SchemaConverter {

	/**
	 * Singleton instance for convinience
	 */
	public static final SchemaConverter INSTANCE = new SchemaConverter();

	public static final String FRAGMENT_XML_ENTRY = "fragment.xml";

	private static final SimpleDateFormat qualifierFormat = new SimpleDateFormat(
			"yyyyMMdd-HHmm");

	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaConverter.class);

	protected SchemaConverter() {
		// noop
	}

	/**
	 * Convert directory given as {@link InputStream} to the resulting archive
	 * 
	 * @param directoryInputStream
	 */
	public Archive createArchive(Set<EPackage> packages, String pluginID, String pathPrefix)
			throws IOException {
		String qualifier = qualifierFormat.format(Calendar.getInstance()
				.getTime());

		Archive archive = new Archive(pluginID + "_1.0.0.v" + qualifier
				+ ".jar");
		StringBuilder pluginBuilder = new StringBuilder(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
						+ "<?eclipse version=\"3.0\"?>\n" + "<plugin>\n");
		StringBuilder xmlExtension = new StringBuilder(
				"\t<extension point=\"org.eclipse.wst.xml.core.catalogContributions\"><catalogContribution>\n");

		for (EPackage pkg : packages) {
			ResourceSet rs = createResourceSet();
			Resource resource = addSchemaResource(rs, pkg);
			EObject obj = resource.getContents().get(0);
			String fileName = resource.getURI().lastSegment();
			String ecoreEntryPath = pathPrefix + "/" + fileName;
			xmlExtension.append(saveSchema(archive, ecoreEntryPath, resource,
					((XSDSchema) obj).getTargetNamespace(), pluginID));
			// Save memory
			System.gc();
		}

		xmlExtension.append("\t</catalogContribution></extension>\n");
		pluginBuilder.append(xmlExtension);
		pluginBuilder.append("</plugin>");
		archive.addEntry(FRAGMENT_XML_ENTRY, pluginBuilder.toString());

		return archive;
	}

	private Resource addSchemaResource(ResourceSet rs, EPackage pkg) {
		String message = pkg.getName();
		// Creating XSD resource
		LOGGER.debug(pkg.getName() + " schema generation start");
		Resource xsd = null;
		long start = System.currentTimeMillis();
		try {
			CustomSchemaBuilder schemaBuilder = new CustomSchemaBuilder(
					ExtendedMetaData.INSTANCE);
			XSDSchema schema = schemaBuilder.getSchema(pkg);
			xsd = rs.createResource(URI.createFileURI(message
					+ ".xsd"));
			if (!xsd.getContents().isEmpty()) {
				throw new RuntimeException("Duplicate schema "
						+ xsd.getURI());
			}
			xsd.getContents().add(schema);
		} catch (Exception e) {
			LOGGER.error("Failed to generate schema for " + pkg.getNsURI(), e);
		}
		LOGGER.info(pkg.getName() + " schema generation took " + (System.currentTimeMillis() - start) / 1000f
				+ " sec.");
		return xsd;
	}

	private String saveSchema(Archive archive, String entryPath,
			Resource resource, String ns, String pluginID) {
		StringBuilder result = new StringBuilder();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LOGGER.info("Saving XML Schema " + ns);
		try {
			resource.save(out, null);
			if (archive.getEntries().containsKey(entryPath)) {
				throw new RuntimeException("Duplicate entry " + entryPath);
			}
			archive.addEntry(entryPath, out.toByteArray());
			result.append("\t<uri name=\"");
			result.append(ns);
			result.append("\" uri=\"platform:/fragment/" + pluginID + "/");
			result.append(entryPath);
			result.append("\"/>\n");
		} catch (Exception e) {
			LOGGER.error("Failed to save XML Schema " + ns, e);
		}
		return result.toString();
	}

	private ResourceSet createResourceSet() {
		ResourceSet resourceSet = new ResourceSetImpl();
		/*
		 * Register XML Factory implementation using DEFAULT_EXTENSION
		 */
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap()
				.put("xsd", new XSDResourceFactoryImpl());

		return resourceSet;
	}

	/**
	 * Converts a single {@link Edimap} to XML Schema
	 * 
	 * @param pkg
	 * @param out
	 * @throws IOException 
	 */
	public void convertEDIMap(EPackage pkg, OutputStream out) throws IOException {
		ResourceSet rs = createResourceSet();
		Resource resource = addSchemaResource(rs, pkg);
		resource.save(out, null);
		resource = null;
		System.gc();
	}

}
