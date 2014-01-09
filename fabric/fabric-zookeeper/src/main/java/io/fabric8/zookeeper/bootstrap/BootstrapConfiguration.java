package io.fabric8.zookeeper.bootstrap;

/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import io.fabric8.api.Constants;
import io.fabric8.api.CreateEnsembleOptions;
import io.fabric8.api.DataStoreRegistrationHandler;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.utils.HostUtils;
import io.fabric8.utils.Ports;
import io.fabric8.utils.SystemProperties;
import io.fabric8.zookeeper.ZkDefs;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
@Component(name = BootstrapConfiguration.COMPONENT_NAME, label = "Fabric8 Bootstrap Configuration", immediate = true, metatype = false)
@Service( BootstrapConfiguration.class )
public class BootstrapConfiguration extends AbstractComponent {

    static final Logger LOGGER = LoggerFactory.getLogger(BootstrapConfiguration.class);

    public static final String COMPONENT_NAME = "io.fabric8.zookeeper.configuration";

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = RuntimeProperties.class)
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();
    @Reference(referenceInterface = DataStoreRegistrationHandler.class)
    private final ValidatingReference<DataStoreRegistrationHandler> registrationHandler = new ValidatingReference<DataStoreRegistrationHandler>();

    private CreateEnsembleOptions options;

    @Activate
    @SuppressWarnings("unchecked")
    void activate() throws Exception {

        RuntimeProperties sysprops = runtimeProperties.get();
        String karafHome = sysprops.getProperty(SystemProperties.KARAF_HOME);

        // [TODO] abstract access to karaf users.properties
        org.apache.felix.utils.properties.Properties userProps = null;
        try {
            userProps = new org.apache.felix.utils.properties.Properties(new File(karafHome + "/etc/users.properties"));
        } catch (IOException e) {
            LOGGER.warn("Failed to load users from etc/users.properties. No users will be imported.", e);
        }

        options = CreateEnsembleOptions.builder().fromRuntimeProperties(sysprops).users(userProps).build();
        if (options.isEnsembleStart()) {
            String connectionUrl = getConnectionUrl(options);
            registrationHandler.get().setRegistrationCallback(new DataStoreBootstrapTemplate(sysprops, connectionUrl, options));

            createOrUpdateDataStoreConfig(options);
            createZooKeeeperServerConfig(options);
            createZooKeeeperClientConfig(connectionUrl, options);

            resetEnsembleAutoStart(sysprops);
        }

        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    private void resetEnsembleAutoStart(RuntimeProperties sysprops) throws IOException {
        String karafHome = sysprops.getProperty(SystemProperties.KARAF_HOME);
        sysprops.setProperty(CreateEnsembleOptions.ENSEMBLE_AUTOSTART, Boolean.FALSE.toString());
        File file = new File(karafHome + "/etc/system.properties");
        org.apache.felix.utils.properties.Properties props = new org.apache.felix.utils.properties.Properties(file);
        props.put(CreateEnsembleOptions.ENSEMBLE_AUTOSTART, Boolean.FALSE.toString());
        props.save();
    }

    public CreateEnsembleOptions getBootstrapOptions() {
        assertValid();
        return options;
    }

    public String getConnectionUrl(CreateEnsembleOptions options) throws UnknownHostException {
        int zooKeeperServerConnectionPort = options.getZooKeeperServerConnectionPort();
        String connectionUrl = getConnectionAddress() + ":" + zooKeeperServerConnectionPort;
        return connectionUrl;
    }

    public void createOrUpdateDataStoreConfig(CreateEnsembleOptions options) throws IOException {
        Configuration config = configAdmin.get().getConfiguration(Constants.DATASTORE_TYPE_PID, null);
        Dictionary<String, Object> properties = config.getProperties();
        if (properties == null || properties.isEmpty()) {
            boolean updateConfig = false;
            properties = new Hashtable<String, Object>();
            Map<String, String> dataStoreProperties = options.getDataStoreProperties();
            for (Map.Entry<String, String> entry : dataStoreProperties.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                Object oldval = properties.put(key, value);
                updateConfig = updateConfig || !value.equals(oldval);
            }
            if (updateConfig) {
                config.update(properties);
            }
        }
    }

    /**
     * Creates ZooKeeper server configuration
     */
    public void createZooKeeeperServerConfig(CreateEnsembleOptions options) throws IOException {
        int serverPort = Ports.mapPortToRange(options.getZooKeeperServerPort(), options.getMinimumPort(), options.getMaximumPort());
        String serverHost = options.getBindAddress();
        Dictionary<String, Object> properties = new Hashtable<String, Object>();
        if (options.isAutoImportEnabled()) {
            loadPropertiesFrom(properties, options.getImportPath() + "/fabric/configs/versions/1.0/profiles/default/io.fabric8.zookeeper.server.properties");
        }
        properties.put("tickTime", String.valueOf(options.getZooKeeperServerTickTime()));
        properties.put("initLimit", String.valueOf(options.getZooKeeperServerInitLimit()));
        properties.put("syncLimit", String.valueOf(options.getZooKeeperServerSyncLimit()));
        properties.put("dataDir", options.getZooKeeperServerDataDir() + "/" + "0000");
        properties.put("clientPort", Integer.toString(serverPort));
        properties.put("clientPortAddress", serverHost);
        properties.put("fabric.zookeeper.pid", "io.fabric8.zookeeper.server-0000");
        Configuration config = configAdmin.get().createFactoryConfiguration(Constants.ZOOKEEPER_SERVER_PID, null);
        config.update(properties);
    }

    /**
     * Creates ZooKeeper client configuration.
     */
    public void createZooKeeeperClientConfig(String connectionUrl, CreateEnsembleOptions options) throws IOException {
        Dictionary<String, Object> properties = new Hashtable<String, Object>();
        if (options.isAutoImportEnabled()) {
            loadPropertiesFrom(properties, options.getImportPath() + "/fabric/configs/versions/1.0/profiles/default/io.fabric8.zookeeper.properties");
        }
        properties.put("zookeeper.url", connectionUrl);
        properties
                .put("zookeeper.timeout", System.getProperties().containsKey("zookeeper.timeout") ? System.getProperties().getProperty("zookeeper.timeout") : "30000");
        properties.put("fabric.zookeeper.pid", Constants.ZOOKEEPER_CLIENT_PID);
        properties.put("zookeeper.password", options.getZookeeperPassword());
        Configuration config = configAdmin.get().getConfiguration(Constants.ZOOKEEPER_CLIENT_PID, null);
        config.update(properties);
    }

    private String getConnectionAddress() throws UnknownHostException {
        RuntimeProperties sysprops = runtimeProperties.get();
        String resolver = sysprops.getProperty(ZkDefs.LOCAL_RESOLVER_PROPERTY, sysprops.getProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY, ZkDefs.LOCAL_HOSTNAME));
        if (resolver.equals(ZkDefs.LOCAL_HOSTNAME)) {
            return HostUtils.getLocalHostName();
        } else if (resolver.equals(ZkDefs.LOCAL_IP)) {
            return HostUtils.getLocalIp();
        } else if (resolver.equals(ZkDefs.MANUAL_IP) && sysprops.getProperty(ZkDefs.MANUAL_IP, null) != null) {
            return sysprops.getProperty(ZkDefs.MANUAL_IP, null);
        } else
            return HostUtils.getLocalHostName();
    }

    private void loadPropertiesFrom(Dictionary<String, Object> dictionary, String from) {
        InputStream is = null;
        Properties properties = new Properties();
        try {
            is = new FileInputStream(from);
            properties.load(is);
            for (String key : properties.stringPropertyNames()) {
                dictionary.put(key, properties.get(key));
            }
        } catch (Exception e) {
            // Ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }

    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }

    void bindRegistrationHandler(DataStoreRegistrationHandler service) {
        this.registrationHandler.bind(service);
    }

    void unbindRegistrationHandler(DataStoreRegistrationHandler service) {
        this.registrationHandler.unbind(service);
    }
}
