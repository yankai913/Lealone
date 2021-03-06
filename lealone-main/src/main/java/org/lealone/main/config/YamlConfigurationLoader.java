/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.main.config;

import java.beans.IntrospectionException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.lealone.common.exceptions.ConfigurationException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.IOUtils;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.introspector.MissingProperty;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

public class YamlConfigurationLoader implements ConfigurationLoader {
    private static final Logger logger = LoggerFactory.getLogger(YamlConfigurationLoader.class);

    private final static String DEFAULT_CONFIGURATION = "lealone.yaml";

    /**
     * Inspect the classpath to find storage configuration file
     */
    private URL getStorageConfigURL() throws ConfigurationException {
        String configUrl = Config.getProperty("config");
        if (configUrl == null)
            configUrl = DEFAULT_CONFIGURATION;

        URL url;
        try {
            url = new URL(configUrl);
            url.openStream().close(); // catches well-formed but bogus URLs
        } catch (Exception e) {
            ClassLoader loader = YamlConfigurationLoader.class.getClassLoader();
            url = loader.getResource(configUrl);
            if (url == null) {
                String required = "file:" + File.separator + File.separator;
                if (!configUrl.startsWith(required))
                    throw new ConfigurationException(
                            "Expecting URI in variable: [lealone.config].  Please prefix the file with " + required
                                    + File.separator + " for local files or " + required + "<server>" + File.separator
                                    + " for remote files.  Aborting.");
                throw new ConfigurationException("Cannot locate " + configUrl
                        + ".  If this is a local file, please confirm you've provided " + required + File.separator
                        + " as a URI prefix.");
            }
        }

        return url;
    }

    @Override
    public Config loadConfig() throws ConfigurationException {
        return loadConfig(getStorageConfigURL());
    }

    public Config loadConfig(URL url) throws ConfigurationException {
        try {
            logger.info("Loading settings from {}", url);
            byte[] configBytes;
            try (InputStream is = url.openStream()) {
                configBytes = IOUtils.toByteArray(is);
            } catch (IOException e) {
                // getStorageConfigURL should have ruled this out
                throw new AssertionError(e);
            }

            Constructor configConstructor = new Constructor(Config.class);

            TypeDescription engineDesc = new TypeDescription(PluggableEngineDef.class);
            engineDesc.putMapPropertyType("parameters", String.class, String.class);
            configConstructor.addTypeDescription(engineDesc);

            MissingPropertiesChecker propertiesChecker = new MissingPropertiesChecker();
            configConstructor.setPropertyUtils(propertiesChecker);
            Yaml yaml = new Yaml(configConstructor);
            Config result = yaml.loadAs(new ByteArrayInputStream(configBytes), Config.class);
            propertiesChecker.check();
            return result;
        } catch (YAMLException e) {
            throw new ConfigurationException("Invalid yaml", e);
        }
    }

    private static class MissingPropertiesChecker extends PropertyUtils {
        private final Set<String> missingProperties = new HashSet<>();

        public MissingPropertiesChecker() {
            setSkipMissingProperties(true);
        }

        @Override
        public Property getProperty(Class<? extends Object> type, String name) throws IntrospectionException {
            Property result = super.getProperty(type, name);
            if (result instanceof MissingProperty) {
                missingProperties.add(result.getName());
            }
            return result;
        }

        public void check() throws ConfigurationException {
            if (!missingProperties.isEmpty()) {
                throw new ConfigurationException("Invalid yaml. Please remove properties " + missingProperties
                        + " from your lealone.yaml");
            }
        }
    }
}
