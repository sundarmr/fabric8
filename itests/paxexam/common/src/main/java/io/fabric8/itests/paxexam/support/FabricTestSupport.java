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
package io.fabric8.itests.paxexam.support;

import io.fabric8.api.Container;
import io.fabric8.api.CreateChildContainerOptions;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.api.ServiceProxy;
import io.fabric8.api.Version;
import io.fabric8.tooling.testing.pax.exam.karaf.FabricKarafTestSupport;
import io.fabric8.tooling.testing.pax.exam.karaf.ServiceLocator;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.service.command.Function;
import org.junit.Assert;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.karaf.options.DoNotModifyLogOption;
import org.ops4j.pax.exam.karaf.options.KarafDistributionOption;
import org.ops4j.pax.exam.options.DefaultCompositeOption;

import static io.fabric8.api.ServiceProxy.createServiceProxy;

public class FabricTestSupport extends FabricKarafTestSupport {

    public static final String FABRIC_ITEST_GROUP_ID = "FABRIC_ITEST_GROUP_ID";
    public static final String FABRIC_ITEST_ARTIFACT_ID = "FABRIC_ITEST_ARTIFACT_ID";

    public static final String GROUP_ID = System.getenv().containsKey(FABRIC_ITEST_GROUP_ID) ? System.getenv(FABRIC_ITEST_GROUP_ID) : "io.fabric8";
    public static final String ARTIFACT_ID = System.getenv().containsKey(FABRIC_ITEST_ARTIFACT_ID) ? System.getenv(FABRIC_ITEST_ARTIFACT_ID) : "fabric8-karaf";

    static final String KARAF_GROUP_ID = "org.apache.karaf";
    static final String KARAF_ARTIFACT_ID = "apache-karaf";

    static final String KARAF_VERSION = "2.4.0";

    private Container createChildContainer(FabricService fabricService, String name, String parent, String profileName, String jvmOpts) throws Exception {

        Thread.sleep(DEFAULT_WAIT);

        Container parentContainer = fabricService.getContainer(parent);
        Assert.assertNotNull(parentContainer);

        CreateChildContainerOptions.Builder builder = CreateChildContainerOptions.builder().name(name).parent(parent)
                .zookeeperPassword(fabricService.getZookeeperPassword()).jmxUser("admin").jmxPassword("admin");
        if (jvmOpts != null) {
            builder.jvmOpts(jvmOpts);
        } else {
            builder.jvmOpts("-Xms512m -Xmx1024m -Djava.net.preferIPv4Stack=true -Dpatching.disabled=true -Djava.security.egd=file:/dev/./urandom");
        }

        CreateContainerMetadata[] metadata = fabricService.createContainers(builder.build());
        if (metadata.length > 0) {
            if (metadata[0].getFailure() != null) {
                throw new Exception("Error creating child container:" + name, metadata[0].getFailure());
            }
            Container container = metadata[0].getContainer();
            Version version = fabricService.getRequiredDefaultVersion();
            Profile profile = version.getRequiredProfile(profileName);
            Assert.assertNotNull("Expected to find profile with name:" + profileName, profile);
            container.setProfiles(new Profile[] { profile });
            Provision.containersStatus(Arrays.asList(container), "success", PROVISION_TIMEOUT);
            return container;
        }
        throw new Exception("Could container not created");
    }

    private void destroyChildContainer(FabricService fabricService, CuratorFramework curator, String name) throws InterruptedException {
        try {
            //Wait for zookeeper service to become available.
            Thread.sleep(DEFAULT_WAIT);
            //We want to check if container exists before we actually delete them.
            //We need this because getContainer will create a container object if container doesn't exists.
            if (ZooKeeperUtils.exists(curator, ZkPath.CONTAINER.getPath(name)) != null) {
                Container container = fabricService.getContainer(name);
                //We want to go through container destroy method so that cleanup methods are properly invoked.
                container.destroy();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Creates a child container, waits for successfull provisioning and asserts, its assigned the right profile.
     */
    protected Container createAndAssertChildContainer(FabricService fabricService, String name, String parent, String profile) throws Exception {
        return createAndAssertChildContainer(fabricService, name, parent, profile, null);
    }

    private Container createAndAssertChildContainer(FabricService fabricService, String name, String parent, String profile, String jvmOpts) throws Exception {
        Container child1 = createChildContainer(fabricService, name, parent, profile, jvmOpts);
        Container result = fabricService.getContainer(name);
        Assert.assertEquals("Containers should have the same id", child1.getId(), result.getId());
        return result;
    }

    /**
     * Cleans a containers profile by switching to default profile and resetting the profile.
     */
    private boolean containerSetProfile(FabricService fabricService, CuratorFramework curator, String containerName, String profileName) throws Exception {
        return containerSetProfile(fabricService, curator, containerName, profileName, true);
    }

    /**
     * Cleans a containers profile by switching to default profile and resetting the profile.
     */
    private boolean containerSetProfile(FabricService fabricService, CuratorFramework curator, String containerName, String profileName, Boolean waitForProvision)
            throws Exception {
        System.out.println("Switching profile: " + profileName + " on container:" + containerName);

        Container container = fabricService.getContainer(containerName);
        Version version = container.getVersion();
        Profile[] profiles = new Profile[] { version.getRequiredProfile(profileName) };
        Profile[] currentProfiles = container.getProfiles();

        Arrays.sort(profiles);
        Arrays.sort(currentProfiles);

        boolean same = true;
        if (profiles.length != currentProfiles.length) {
            same = false;
        } else {
            for (int i = 0; i < currentProfiles.length; i++) {
                if (!currentProfiles[i].equals(profiles[i])) {
                    same = false;
                }
            }
        }

        if (!same && waitForProvision) {
            //This is required so that waitForProvisionSuccess doesn't retrun before the deployment agent kicks in.
            ZooKeeperUtils.setData(curator, ZkPath.CONTAINER_PROVISION_RESULT.getPath(containerName), "switching profile");
            container.setProfiles(profiles);
            Provision.containersStatus(Arrays.asList(container), "success", PROVISION_TIMEOUT);
        }
        return same;
    }

    private void addStagingRepoToDefaultProfile() {
        executeCommand("fabric:profile-edit -p io.fabric8.agent/org.ops4j.pax.url.mvn.repositories=" + "http://repo1.maven.org/maven2,"
                + "https://repository.jboss.org/nexus/content/repositories/fs-releases/,"
                + "https://repository.jboss.org/nexus/content/repositories/fs-snapshots//@snapshots@noreleases,"
                + "http://repository.apache.org/content/groups/snapshots-group@snapshots@noreleases," + "http://svn.apache.org/repos/asf/servicemix/m2-repo,"
                + "http://repository.springsource.com/maven/bundles/release," + "http://repository.springsource.com/maven/bundles/external,"
                + "https://repository.jboss.org/nexus/content/groups/ea" + " default");
    }

    protected void waitForFabricCommands() {
        ServiceLocator.awaitService(bundleContext, Function.class, "(&(osgi.command.scope=fabric)(osgi.command.function=profile-edit))");
    }

    /**
     * Returns the Version of Karaf to be used.
     */
    protected String getKarafVersion() {
        String answer = KARAF_VERSION;
        System.out.println("*** Using Karaf version: " + answer + " ***");
        return answer;
    }

    /**
     * Create an {@link Option} for using a Fabric distribution.
     */
    protected Option[] fabricDistributionConfiguration() {
        return new Option[] {
                KarafDistributionOption.karafDistributionConfiguration()
                        .frameworkUrl(CoreOptions.maven().groupId(GROUP_ID).artifactId(ARTIFACT_ID).versionAsInProject().type("zip")).karafVersion(getKarafVersion()).useDeployFolder(false)
                        .name("Fabric Karaf Distro").unpackDirectory(new File("target/paxexam/unpack/")), KarafDistributionOption.useOwnExamBundlesStartLevel(50),
                envAsSystemProperty(ContainerBuilder.CONTAINER_TYPE_PROPERTY, "child"), envAsSystemProperty(ContainerBuilder.CONTAINER_NUMBER_PROPERTY, "1"),
                envAsSystemProperty(SshContainerBuilder.SSH_HOSTS_PROPERTY), envAsSystemProperty(SshContainerBuilder.SSH_USERS_PROPERTY),
                envAsSystemProperty(SshContainerBuilder.SSH_PASSWORD_PROPERTY), envAsSystemProperty(SshContainerBuilder.SSH_RESOLVER_PROPERTY),
                KarafDistributionOption.editConfigurationFilePut("etc/system.properties", "patching.disabled", "true"),
                KarafDistributionOption.editConfigurationFilePut("etc/system.properties", "java.security.egd", "file:/dev/./urandom"),
                //Add Pax Logging to the default profile so that child containers store their logs under the root containers data folder. This way we retain logs even after containers are destroyed.
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.rootLogger", "INFO, out, osgi:*"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.appender.out", "org.apache.log4j.RollingFileAppender"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.appender.out.layout", "org.apache.log4j.PatternLayout"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.appender.out.layout.ConversionPattern", "%d{ISO8601} | %-5.5p | %-16.16t | %-32.32c{1} | %-32.32C %4L | %X{bundle.id} - %X{bundle.name} - %X{bundle.version} | %m%n"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.appender.out.maxFileSize", "10MB"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.appender.out.append", "true"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/org.ops4j.pax.logging.properties", "log4j.appender.out.file", "${runtime.home}/data/log/karaf-${runtime.id}.log"),

                KarafDistributionOption.editConfigurationFilePut("etc/org.apache.felix.fileinstall-deploy.cfg", "felix.fileinstall.active.level", "45"),
                KarafDistributionOption.editConfigurationFilePut("etc/org.apache.felix.fileinstall-deploy.cfg", "felix.fileinstall.noInitialDelay", "true"),
                KarafDistributionOption.editConfigurationFilePut("etc/org.apache.felix.fileinstall-deploy.cfg", "felix.fileinstall.poll", "250"),
                KarafDistributionOption.editConfigurationFilePut("etc/config.properties", "karaf.startlevel.bundle", "50"),
                KarafDistributionOption.editConfigurationFilePut("etc/config.properties", "karaf.startup.message", "Loading Fabric from: ${runtime.home}"),
                KarafDistributionOption.editConfigurationFilePut("etc/users.properties", "admin", "admin,admin"),
                CoreOptions.mavenBundle("io.fabric8.itests.paxexam", "fabric-itests-paxexam-common").versionAsInProject(),
                CoreOptions.mavenBundle("io.fabric8.tooling.testing", "pax-exam-karaf").versionAsInProject(), new DoNotModifyLogOption(),
                KarafDistributionOption.keepRuntimeFolder(),
                // See ENTESB-3731
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.maven.properties", "io.fabric8.maven.localRepository", "${karaf.data}/repository"),
                // disable fetching maven-metadata.xml for SNAPSHOTS
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties", "org.ops4j.pax.url.mvn.globalUpdatePolicy", "never"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties", "org.ops4j.pax.url.mvn.globalChecksumPolicy", "ignore"),
                KarafDistributionOption.editConfigurationFilePut("etc/org.ops4j.pax.url.mvn.cfg", "org.ops4j.pax.url.mvn.globalUpdatePolicy", "never"),
                KarafDistributionOption.editConfigurationFilePut("etc/org.ops4j.pax.url.mvn.cfg", "org.ops4j.pax.url.mvn.globalChecksumPolicy", "ignore")
        };
    }

    protected Option[] managedFabricDistributionConfiguration() {
        return new Option[] {
                new DefaultCompositeOption(fabricDistributionConfiguration()),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties",
                        "ignore.probe", "^PAXEXAM-PROBE"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties",
                        "repository.pax-exam", "file:examfeatures.xml"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties",
                        "feature.pax-exam", "exam"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties",
                        "bundle.probe", "local"),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties",
                        "bundle.tooling-testing",
                        "mvn:io.fabric8.tooling.testing/pax-exam-karaf/" + MavenUtils.getArtifactVersion("io.fabric8.tooling.testing", "pax-exam-karaf")),
                KarafDistributionOption.editConfigurationFilePut("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties",
                        "bundle.itests-common",
                        "mvn:io.fabric8.itest.paxexam/fabric-itests-paxexam-common/" + MavenUtils.getArtifactVersion("io.fabric8.itests.paxexam", "fabric-itests-paxexam-common")), };
    }

    private Object getMBean(Container container, ObjectName mbeanName, Class clazz) throws Exception {
        JMXServiceURL url = new JMXServiceURL(container.getJmxUrl());
        Map env = new HashMap();
        String[] creds = { "admin", "admin" };
        env.put(JMXConnector.CREDENTIALS, creds);
        JMXConnector jmxc = JMXConnectorFactory.connect(url, env);
        MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
        return JMX.newMBeanProxy(mbsc, mbeanName, clazz, true);
    }

    protected Option envAsSystemProperty(String name) {
        return envAsSystemProperty(name, "");
    }

    protected Option envAsSystemProperty(String name, String defaultValue) {
        String value = System.getenv(name);
        return KarafDistributionOption.editConfigurationFilePut("etc/system.properties", name, (value != null && !value.isEmpty()) ? value : defaultValue);
    }

    // Testing helpers

    /**
     * Retrieves fresh instance of proxied {@link io.fabric8.api.FabricService}.
     *
     * @return New proxy of {@link io.fabric8.api.FabricService}.
     */
    protected ServiceProxy<FabricService> fabricServiceProxy() {
        return createServiceProxy(bundleContext, FabricService.class);
    }

}
