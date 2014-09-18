/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service;

import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.configuration.ConfigurationException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.codehaus.jackson.map.ObjectMapper;
import org.gluu.site.ldap.OperationsFacade;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.jboss.seam.Component;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Factory;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Observer;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.Startup;
import org.jboss.seam.annotations.async.Asynchronous;
import org.jboss.seam.async.TimerSchedule;
import org.jboss.seam.contexts.Context;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.core.Events;
import org.jboss.seam.log.Log;
import org.xdi.model.SimpleProperty;
import org.xdi.model.SmtpConfiguration;
import org.xdi.model.ldap.GluuLdapConfiguration;
import org.xdi.oxauth.model.appliance.GluuAppliance;
import org.xdi.oxauth.model.config.ConfigurationFactory;
import org.xdi.oxauth.model.config.oxIDPAuthConf;
import org.xdi.oxauth.util.FileConfiguration;
import org.xdi.service.PythonService;
import org.xdi.service.ldap.LdapConnectionService;
import org.xdi.util.StringHelper;
import org.xdi.util.security.PropertiesDecrypter;
import org.xdi.util.security.StringEncrypter;
import org.xdi.util.security.StringEncrypter.EncryptionException;

import com.unboundid.ldap.sdk.ResultCode;

/**
 * @author Javier Rojas Blum
 * @author Yuriy Zabrovarnyy
 * @author Yuriy Movchan
 * @version 0.1, 10.24.2011
 */
@Scope(ScopeType.APPLICATION)
@Name("appInitializer")
@Startup
public class AppInitializer {

	private final static String EVENT_TYPE = "AppInitializerTimerEvent";
    private final static int DEFAULT_INTERVAL = 30; // 30 seconds

    public  static final String LDAP_AUTH_CONFIG_NAME = "ldapAuthConfig";

    public  static final String LDAP_ENTRY_MANAGER_NAME = "ldapEntryManager";
    public  static final String LDAP_AUTH_ENTRY_MANAGER_NAME = "ldapAuthEntryManager";

    @Logger
    private Log log;
    
    @In
    private ApplianceService applianceService;

	private FileConfiguration ldapConfig;
	private List<GluuLdapConfiguration> ldapAuthConfigs;

	private LdapConnectionService connectionProvider;
	private LdapConnectionService bindConnectionProvider;

	private List<LdapConnectionService> authConnectionProviders;
	private List<LdapConnectionService> authBindConnectionProviders;

    private AtomicBoolean isActive;
	private long lastFinishedTime;

    @Create
    public void createApplicationComponents() throws ConfigurationException {
        createConnectionProvider();
        ConfigurationFactory.create();

        List<GluuLdapConfiguration> ldapAuthConfigs = loadLdapAuthConfigs((LdapEntryManager) Component.getInstance(LDAP_ENTRY_MANAGER_NAME, true));
        reloadConfigurationImpl(ldapAuthConfigs);

        createAuthConnectionProviders(ldapAuthConfigs);
        
        addSecurityProviders();
        PythonService.instance().initPythonInterpreter();
    }

    @Observer("org.jboss.seam.postInitialization")
    public void initReloadTimer() {
		this.isActive = new AtomicBoolean(false);
		this.lastFinishedTime = System.currentTimeMillis();

		Events.instance().raiseTimedEvent(EVENT_TYPE, new TimerSchedule(1 * 60 * 1000L, DEFAULT_INTERVAL * 1000L));
    }

	@Observer(EVENT_TYPE)
	@Asynchronous
	public void reloadConfigurationTimerEvent() {
		if (this.isActive.get()) {
			return;
		}

		if (!this.isActive.compareAndSet(false, true)) {
			return;
		}

		try {
			reloadConfiguration();
		} catch (Throwable ex) {
			log.error("Exception happened while reloading application configuration", ex);
		} finally {
			this.isActive.set(false);
			this.lastFinishedTime = System.currentTimeMillis();
		}
	}

	private void reloadConfiguration() {
		List<GluuLdapConfiguration> newLdapAuthConfigs = loadLdapAuthConfigs((LdapEntryManager) Component.getInstance(LDAP_ENTRY_MANAGER_NAME, true));

		reloadConfigurationImpl(newLdapAuthConfigs);
	}

	private void reloadConfigurationImpl(List<GluuLdapConfiguration> currLdapAuthConfigs) {
		List<GluuLdapConfiguration> newLdapAuthConfigs = null;
		if (currLdapAuthConfigs.size() > 0) {
			newLdapAuthConfigs = currLdapAuthConfigs;
		}

		this.ldapAuthConfigs = newLdapAuthConfigs;

		Context applicationContext = Contexts.getApplicationContext();
        if (newLdapAuthConfigs == null) {
        	if (applicationContext.isSet(LDAP_AUTH_CONFIG_NAME)) {
        		applicationContext.remove(LDAP_AUTH_CONFIG_NAME);
        	}
        } else {
        	applicationContext.set(LDAP_AUTH_CONFIG_NAME, newLdapAuthConfigs);
        }
	}

	private void addSecurityProviders() {
        try {
            final Provider[] providers = Security.getProviders();
            if (providers != null) {
                boolean hasBC = false;
                for (Provider p : providers) {
                    if (p.getName().equalsIgnoreCase("BC")) {
                        hasBC = true;
                    }
                }
                if (!hasBC) {
                    Security.addProvider(new BouncyCastleProvider());
                }
            }
        } catch (Exception e) {
            log.trace(e.getMessage(), e);
        }
    }

    @Factory(value = LDAP_ENTRY_MANAGER_NAME, scope = ScopeType.APPLICATION, autoCreate = true)
    public LdapEntryManager createLdapEntryManager() {
        LdapEntryManager ldapEntryManager = new LdapEntryManager(new OperationsFacade(this.connectionProvider, this.bindConnectionProvider));
        log.debug("Created {0}: {1}", LDAP_ENTRY_MANAGER_NAME, ldapEntryManager);
        return ldapEntryManager;
    }

	@Factory(value = LDAP_AUTH_ENTRY_MANAGER_NAME, scope = ScopeType.APPLICATION, autoCreate = true)
	public List<LdapEntryManager> createLdapAuthEntryManager() {
		List<LdapEntryManager> ldapAuthEntryManagers = new ArrayList<LdapEntryManager>();
		if (this.ldapAuthConfigs == null) {
			return ldapAuthEntryManagers;
		}

		for (int i = 0; i < this.ldapAuthConfigs.size(); i++) {
			LdapEntryManager ldapAuthEntryManager = new LdapEntryManager(new OperationsFacade(this.authConnectionProviders.get(i), this.authBindConnectionProviders.get(i)));
	        log.debug("Created {0}#{1}: {2}", LDAP_AUTH_ENTRY_MANAGER_NAME, i, ldapAuthEntryManager);
	        
	        ldapAuthEntryManagers.add(ldapAuthEntryManager);
		}

		return ldapAuthEntryManagers;
	}

	@Factory(value = "smtpConfiguration", scope = ScopeType.APPLICATION, autoCreate = true)
	public SmtpConfiguration createSmtpConfiguration() {
		GluuAppliance appliance = applianceService.getAppliance();
		SmtpConfiguration smtpConfiguration = appliance.getSmtpConfiguration();
		
		if (smtpConfiguration == null) {
			return null;
		}

		String password = smtpConfiguration.getPassword();
		if (StringHelper.isNotEmpty(password)) {
			try {
				smtpConfiguration.setPasswordDecrypted(StringEncrypter.defaultInstance().decrypt(password));
			} catch (EncryptionException ex) {
				log.error("Failed to decript SMTP user password", ex);
			}
		}
		
		return smtpConfiguration;
	}

    private void createConnectionProvider() throws ConfigurationException {
        this.ldapConfig =  ConfigurationFactory.getLdapConfiguration();

        Properties connectionProperties = (Properties) this.ldapConfig.getProperties();
        this.connectionProvider = createConnectionProvider(connectionProperties);

        Properties bindConnectionProperties = prepareBindConnectionProperties(connectionProperties);
        this.bindConnectionProvider = createBindConnectionProvider(bindConnectionProperties, connectionProperties);
    }

    private void createAuthConnectionProviders(List<GluuLdapConfiguration> ldapAuthConfigs) throws ConfigurationException {
    	List<LdapConnectionService> tmpAuthConnectionProviders = new ArrayList<LdapConnectionService>();
    	List<LdapConnectionService> tmpAuthBindConnectionProviders = new ArrayList<LdapConnectionService>();

    	// Prepare connection providers per LDAP authentication configuration
        for (GluuLdapConfiguration ldapAuthConfig : ldapAuthConfigs) {
	        Properties connectionProperties = prepareAuthConnectionProperties(ldapAuthConfig);
	        LdapConnectionService connectionProvider = createConnectionProvider(connectionProperties);
	
	        Properties bindConnectionProperties = prepareBindConnectionProperties(connectionProperties);
	        LdapConnectionService bindConnectionProvider = createBindConnectionProvider(bindConnectionProperties, connectionProperties);

	        tmpAuthConnectionProviders.add(connectionProvider);
	        tmpAuthBindConnectionProviders.add(bindConnectionProvider);
    	}

        this.authConnectionProviders = tmpAuthConnectionProviders;
    	this.authBindConnectionProviders = tmpAuthBindConnectionProviders;
    }

	private Properties prepareAuthConnectionProperties(GluuLdapConfiguration ldapAuthConfig) {
        FileConfiguration configuration = ConfigurationFactory.getLdapConfiguration();

		Properties properties = (Properties) configuration.getProperties().clone();
		if (ldapAuthConfig != null) {
		    properties.setProperty("servers", buildServersString(ldapAuthConfig.getServers()));
		    
		    String bindDn = ldapAuthConfig.getBindDN();
		    if (StringHelper.isNotEmpty(bindDn)) {
		    	properties.setProperty("bindDN", ldapAuthConfig.getBindDN());
		    }
			properties.setProperty("bindPassword", ldapAuthConfig.getBindPassword());
			properties.setProperty("useSSL", Boolean.toString(ldapAuthConfig.isUseSSL()));
		}

		return properties;
	}

    private Properties prepareBindConnectionProperties(Properties connectionProperties) {
		// TODO: Use own properties with prefix specified in variable 'bindConfigurationComponentName'
		Properties bindProperties = (Properties) connectionProperties.clone();
		bindProperties.remove("bindDN");
		bindProperties.remove("bindPassword");

		return bindProperties;
	}

	private LdapConnectionService createConnectionProvider(Properties connectionProperties) {
		LdapConnectionService connectionProvider = new LdapConnectionService(PropertiesDecrypter.decryptProperties(connectionProperties));

		return connectionProvider;
	}

	private LdapConnectionService createBindConnectionProvider(Properties bindConnectionProperties, Properties connectionProperties) {
		LdapConnectionService bindConnectionProvider = createConnectionProvider(bindConnectionProperties);
		if (ResultCode.INAPPROPRIATE_AUTHENTICATION.equals(bindConnectionProvider.getCreationResultCode())) {
			log.warn("It's not possible to create authentication LDAP connection pool using anonymous bind. Attempting to create it using binDN/bindPassword");
			bindConnectionProvider = createConnectionProvider(connectionProperties);
		}
		
		return bindConnectionProvider;
	}

	private String buildServersString(List<SimpleProperty> servers) {
		StringBuilder sb = new StringBuilder();

		if (servers == null) {
			return sb.toString();
		}
		
		boolean first = true;
		for (SimpleProperty server : servers) {
			if (first) {
				first = false;
			} else {
				sb.append(",");
			}

			sb.append(server.getValue());
		}

		return sb.toString();
	}

	public List<oxIDPAuthConf> loadLdapIdpAuthConfigs(LdapEntryManager localLdapEntryManager) {
		String baseDn = ConfigurationFactory.getBaseDn().getAppliance();
		String applianceInum = ConfigurationFactory.getConfiguration().getApplianceInum();
		if (StringHelper.isEmpty(baseDn) || StringHelper.isEmpty(applianceInum)) {
			return null;
		}

		String applianceDn = String.format("inum=%s,%s", applianceInum, baseDn);

		GluuAppliance appliance = null;
		try {
			appliance = localLdapEntryManager.find(GluuAppliance.class, applianceDn);
		} catch (LdapMappingException ex) {
			log.error("Failed to load appliance entry from Ldap", ex);
			return null;
		}

		if ((appliance == null) || (appliance.getOxIDPAuthentication() == null)) {
			return null;
		}

		List<oxIDPAuthConf> configurations = new ArrayList<oxIDPAuthConf>();
		for (String configurationJson : appliance.getOxIDPAuthentication()) {

			try {
				oxIDPAuthConf configuration = (oxIDPAuthConf) jsonToObject(configurationJson, oxIDPAuthConf.class);
				if (configuration.getType().equalsIgnoreCase("ldap") || configuration.getType().equalsIgnoreCase("auth")) {
					configurations.add(configuration);
				}
			} catch (Exception ex) {
				log.error("Failed to create object by json: '{0}'", ex, configurationJson);
			}
		}

		return configurations;
	}

	public GluuLdapConfiguration loadLdapAuthConfig(oxIDPAuthConf configuration) {
		if (configuration == null) {
			return null;
		}

		try {
			if (configuration.getType().equalsIgnoreCase("ldap")) {
				return mapOldLdapConfig(configuration);
			} else if (configuration.getType().equalsIgnoreCase("auth")) {
				return mapLdapConfig(configuration.getConfig());
			}
		} catch (Exception ex) {
			log.error("Failed to create object by oxIDPAuthConf: '{0}'", ex, configuration);
		}

		return null;
	}

	public List<GluuLdapConfiguration> loadLdapAuthConfigs(LdapEntryManager localLdapEntryManager) {
		List<GluuLdapConfiguration> ldapAuthConfigs = new ArrayList<GluuLdapConfiguration>();

		List<oxIDPAuthConf> ldapIdpAuthConfigs = loadLdapIdpAuthConfigs(localLdapEntryManager);
		if (ldapIdpAuthConfigs == null) {
			return ldapAuthConfigs;
		}

		for (oxIDPAuthConf ldapIdpAuthConfig : ldapIdpAuthConfigs) {
			GluuLdapConfiguration ldapAuthConfig = loadLdapAuthConfig(ldapIdpAuthConfig);
			if (ldapAuthConfig != null) {
				ldapAuthConfigs.add(ldapAuthConfig);
			}
		}
		
		return ldapAuthConfigs; 
	}

	@Deprecated
	// Remove it after 2013/10/01
	private GluuLdapConfiguration mapOldLdapConfig(oxIDPAuthConf oneConf) {
		GluuLdapConfiguration ldapConfig = new GluuLdapConfiguration();
		ldapConfig.setServers(Arrays.asList(
				new SimpleProperty(oneConf.getFields().get(0).getValues().get(0) + ":" + oneConf.getFields().get(1).getValues().get(0))));
		ldapConfig.setBindDN(oneConf.getFields().get(2).getValues().get(0));
		ldapConfig.setBindPassword(oneConf.getFields().get(3).getValues().get(0));
		ldapConfig.setUseSSL(Boolean.valueOf(oneConf.getFields().get(4).getValues().get(0)));
		ldapConfig.setMaxConnections(3);
		ldapConfig.setConfigId("auth_ldap_server");
		ldapConfig.setEnabled(oneConf.getEnabled());
		return ldapConfig;
	}

	private GluuLdapConfiguration mapLdapConfig(String config) throws Exception {
		return (GluuLdapConfiguration) jsonToObject(config, GluuLdapConfiguration.class);
	}

	private Object jsonToObject(String json, Class<?> clazz) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Object clazzObject = mapper.readValue(json, clazz);

		return clazzObject;
	}

}