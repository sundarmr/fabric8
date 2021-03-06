/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.agent.service;

import io.fabric8.agent.download.DownloadManager;
import io.fabric8.agent.download.DownloadManagers;
import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.MavenResolvers;
import io.fabric8.maven.url.ServiceConstants;
import org.apache.felix.utils.version.VersionRange;
import org.easymock.EasyMock;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.jar.JarFile.MANIFEST_NAME;
import static org.easymock.EasyMock.*;

public class AgentTest {

    @Test
    public void testAgent() throws Exception {
        System.setProperty("karaf.data", new File("target/karaf/data").getAbsolutePath());
        System.setProperty("karaf.home", new File("target/karaf").getAbsolutePath());

        Dictionary<String, String> resolverProps = new Hashtable<>();
        resolverProps.put(ServiceConstants.PROPERTY_REPOSITORIES, "http://repository.jboss.org/nexus/content/repositories/fs-public/@id=jboss.fs.public,https://repository.jboss.org/nexus/content/groups/ea/@id=jboss.ea.repo,http://repo1.maven.org/maven2@id=maven.central.repo");
        MavenResolver mavenResolver = MavenResolvers.createMavenResolver(resolverProps, null);
        DownloadManager manager = DownloadManagers.createDownloadManager(mavenResolver, Executors.newScheduledThreadPool(8));

        BundleContext systemBundleContext = createMock(BundleContext.class);
        TestSystemBundle systemBundle = createTestSystemBundle("/common", "system-bundle");
        systemBundle.setBundleContext(systemBundleContext);

        Bundle serviceBundle = createTestBundle(1l, Bundle.ACTIVE, "/common", "fabric-agent");

        expect(systemBundleContext.getBundle()).andReturn(systemBundle).anyTimes();

        expect(systemBundleContext.getBundles()).andReturn(new Bundle[]{systemBundle}).anyTimes();

        long nextBundleId = 2;
        List<Bundle> mockBundles = new ArrayList<>();

        String karafVersion = System.getProperty("karaf-version");

        String[] bundles = {
                "mvn:org.apache.aries.blueprint/org.apache.aries.blueprint.api/1.0.1",
                "mvn:org.apache.aries.blueprint/org.apache.aries.blueprint.cm/1.0.6",
                "mvn:org.apache.aries.blueprint/org.apache.aries.blueprint.core/1.4.4",
                "mvn:org.apache.aries.blueprint/org.apache.aries.blueprint.core.compatibility/1.0.0",
                "mvn:org.apache.aries.proxy/org.apache.aries.proxy.api/1.0.1",
                "mvn:org.apache.aries.proxy/org.apache.aries.proxy.impl/1.0.4",
                "mvn:org.apache.aries/org.apache.aries.util/1.1.0",
                "mvn:org.apache.felix/org.apache.felix.configadmin/1.8.4",
                "mvn:org.apache.karaf.jaas/org.apache.karaf.jaas.command/" + karafVersion,
                "mvn:org.apache.karaf.jaas/org.apache.karaf.jaas.config/" + karafVersion,
                "mvn:org.apache.karaf.jaas/org.apache.karaf.jaas.modules/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.commands/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.console/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.dev/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.log/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.osgi/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.packages/" + karafVersion,
                "mvn:org.apache.karaf.shell/org.apache.karaf.shell.ssh/" + karafVersion,
                "mvn:org.apache.mina/mina-core/2.0.9",
                "mvn:org.apache.sshd/sshd-core/0.14.0",
                "mvn:org.ow2.asm/asm-all/5.0.4",
                "mvn:org.ops4j.pax.logging/pax-logging-api/1.8.4",
                "mvn:org.ops4j.pax.logging/pax-logging-service/1.8.4",
        };
        for (String bundleUri : bundles) {
            File file = mavenResolver.download(bundleUri);
            Hashtable<String, String> headers = doGetMetadata(file);
            TestBundle bundle = new TestBundle(++nextBundleId, bundleUri, Bundle.INSTALLED, headers) {
                @Override
                public void setStartLevel(int startlevel) {
                }

                @Override
                public void start() throws BundleException {
                }
            };
            expect(systemBundleContext.installBundle(EasyMock.eq(bundleUri), EasyMock.<InputStream>anyObject())).andReturn(bundle);
        }

        ServiceRegistration registration = EasyMock.createMock(ServiceRegistration.class);
        expect(systemBundleContext.registerService(EasyMock.eq(ResolverHookFactory.class), EasyMock.<ResolverHookFactory>anyObject(), EasyMock.<Dictionary>isNull())).andReturn(registration);
        registration.unregister();

        replay(systemBundleContext, registration);
        for (Bundle bundle : mockBundles) {
            replay(bundle);
        }

        Agent agent = new Agent(serviceBundle, systemBundleContext, manager) {
            @Override
            protected <T> void awaitService(Class<T> serviceClass, String filterspec, int timeout, TimeUnit timeUnit) {
            }
        };

        String karafFeaturesUrl = "mvn:org.apache.karaf.assemblies.features/standard/" + System.getProperty("karaf-version") + "/xml/features";

        agent.provision(
                Collections.singleton(karafFeaturesUrl),
                Collections.singleton("ssh"),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                new HashSet<>(Arrays.asList(
                        "mvn:org.ops4j.pax.logging/pax-logging-api/1.8.4",
                        "mvn:org.ops4j.pax.logging/pax-logging-service/1.8.4",
                        "mvn:org.apache.felix/org.apache.felix.configadmin/1.8.4"
                )),
                Collections.<String, Map<VersionRange, Map<String, String>>>emptyMap()
        );

    }


    private TestBundle createTestBundle(long bundleId, int state, String dir, String name) throws IOException, BundleException {
        URL loc = getClass().getResource(dir + "/" + name + ".mf");
        Manifest man = new Manifest(loc.openStream());
        Hashtable<String, String> headers = new Hashtable<>();
        for (Map.Entry attr : man.getMainAttributes().entrySet()) {
            headers.put(attr.getKey().toString(), attr.getValue().toString());
        }
        return new TestBundle(bundleId, name, state, headers);
    }

    private TestSystemBundle createTestSystemBundle(String dir, String name) throws IOException, BundleException {
        URL loc = getClass().getResource(dir + "/" + name + ".mf");
        Manifest man = new Manifest(loc.openStream());
        Hashtable<String, String> headers = new Hashtable<>();
        for (Map.Entry attr : man.getMainAttributes().entrySet()) {
            headers.put(attr.getKey().toString(), attr.getValue().toString());
        }
        return new TestSystemBundle(headers);
    }

    protected Hashtable<String, String> doGetMetadata(File file) throws IOException {
        try (
                InputStream is = new BufferedInputStream(new FileInputStream(file))
        ) {
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (MANIFEST_NAME.equals(entry.getName())) {
                    Attributes attributes = new Manifest(zis).getMainAttributes();
                    Hashtable<String, String> headers = new Hashtable<>();
                    for (Map.Entry attr : attributes.entrySet()) {
                        headers.put(attr.getKey().toString(), attr.getValue().toString());
                    }
                    return headers;
                }
            }
        }
        throw new IllegalArgumentException("Resource " + file + " does not contain a manifest");
    }

}
