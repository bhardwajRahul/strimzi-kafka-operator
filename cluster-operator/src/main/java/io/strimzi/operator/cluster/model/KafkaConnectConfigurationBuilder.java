/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.common.ClientTls;
import io.strimzi.api.kafka.model.common.GenericSecretSource;
import io.strimzi.api.kafka.model.common.PasswordSecretSource;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthentication;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthenticationOAuth;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthenticationPlain;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthenticationScram;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthenticationScramSha256;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthenticationScramSha512;
import io.strimzi.api.kafka.model.common.authentication.KafkaClientAuthenticationTls;
import io.strimzi.api.kafka.model.connect.KafkaConnectResources;
import io.strimzi.operator.cluster.model.metrics.MetricsModel;
import io.strimzi.operator.cluster.model.metrics.StrimziMetricsReporterConfig;
import io.strimzi.operator.cluster.model.metrics.StrimziMetricsReporterModel;
import io.strimzi.operator.common.Reconciliation;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.strimzi.operator.cluster.model.KafkaConnectCluster.OAUTH_SECRETS_BASE_VOLUME_MOUNT;
import static io.strimzi.operator.cluster.model.KafkaConnectCluster.OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT;
import static io.strimzi.operator.cluster.model.KafkaConnectCluster.PASSWORD_VOLUME_MOUNT;

/**
 * This class is used to generate the Connect configuration template. The template is later passed using a config map to
 * the connect pods. The scripts in the container images will fill in the variables in the template and use the
 * configuration file. This class is using the builder pattern to make it easy to test the different parts etc. To
 * generate the configuration file, it is using the PrintWriter.
 */
public class KafkaConnectConfigurationBuilder {
    // the volume mounted secret file template includes: <volume_mount>/<secret_name>/<secret_key>
    private static final String PLACEHOLDER_VOLUME_MOUNTED_SECRET_TEMPLATE_CONFIG_PROVIDER_DIR = "${strimzidir:%s%s:%s}";
    // the secrets file template: <namespace>/<secret_name>:<secret_key>
    private static final String PLACEHOLDER_SECRET_TEMPLATE_KUBE_CONFIG_PROVIDER = "${strimzisecrets:%s/%s:%s}";

    private final Reconciliation reconciliation;
    private final StringWriter stringWriter = new StringWriter();
    private final PrintWriter writer = new PrintWriter(stringWriter);
    private String securityProtocol = "PLAINTEXT";

    /**
     * Connect configuration template constructor
     *
     * @param reconciliation the reconciliation
     * @param bootstrapServers Kafka cluster bootstrap servers to connect to
     */
    public KafkaConnectConfigurationBuilder(Reconciliation reconciliation, String bootstrapServers) {
        this.reconciliation = reconciliation;
        printHeader();
        printBootstrapServers(bootstrapServers);
    }

    /**
     * Renders the Kafka cluster bootstrap servers configuration
     *
     * @param bootstrapServers  Kafka cluster bootstrap servers to connect to
     */
    private void printBootstrapServers(String bootstrapServers) {
        printSectionHeader("Bootstrap servers");
        writer.println("bootstrap.servers=" + bootstrapServers);
        writer.println();
    }

    /**
     * Configures the Kafka security protocol to be used
     * This internal method is used when the configuration is build, because the security protocol depends on
     * TLS and SASL authentication configurations and if they are set
     */
    private void configureSecurityProtocol() {
        printSectionHeader("Kafka Security protocol");
        writer.println("security.protocol=" + securityProtocol);
        writer.println("producer.security.protocol=" + securityProtocol);
        writer.println("consumer.security.protocol=" + securityProtocol);
        writer.println("admin.security.protocol=" + securityProtocol);
        writer.println();
    }

    /**
     * Adds the TLS/SSL configuration for connecting to the Kafka cluster.
     * The configuration includes the trusted certificates store for TLS connection (server authentication)
     *
     * @param tls   client TLS configuration
     * @param clusterName   name of the cluster
     * @return  the builder instance
     */
    public KafkaConnectConfigurationBuilder withTls(ClientTls tls, String clusterName) {
        if (tls != null) {
            securityProtocol = "SSL";

            if (tls.getTrustedCertificates() != null && !tls.getTrustedCertificates().isEmpty()) {
                printSectionHeader("TLS / SSL");
                String configProviderValue = String.format(PLACEHOLDER_SECRET_TEMPLATE_KUBE_CONFIG_PROVIDER, reconciliation.namespace(), KafkaConnectResources.internalTlsTrustedCertsSecretName(clusterName), "*.crt");
                writer.println("ssl.truststore.certificates=" + configProviderValue);
                writer.println("ssl.truststore.type=PEM");

                writer.println("producer.ssl.truststore.certificates=" + configProviderValue);
                writer.println("producer.ssl.truststore.type=PEM");

                writer.println("consumer.ssl.truststore.certificates=" + configProviderValue);
                writer.println("consumer.ssl.truststore.type=PEM");

                writer.println("admin.ssl.truststore.certificates=" + configProviderValue);
                writer.println("admin.ssl.truststore.type=PEM");

                writer.println();
            }
        }
        return this;
    }

    /**
     * Adds keystore configuration for mTLS (client authentication)
     * or the SASL configuration for client authentication to the Kafka cluster
     *
     * @param authentication authentication configuration
     * @param clusterName name of the cluster
     * @return  the builder instance
     */
    public KafkaConnectConfigurationBuilder withAuthentication(KafkaClientAuthentication authentication, String clusterName) {
        if (authentication != null) {
            printSectionHeader("Authentication configuration");
            // configuring mTLS (client TLS authentication) if TLS client authentication is set
            if (authentication instanceof KafkaClientAuthenticationTls tlsAuth && tlsAuth.getCertificateAndKey() != null) {
                String certConfigProviderValue = String.format(PLACEHOLDER_SECRET_TEMPLATE_KUBE_CONFIG_PROVIDER, reconciliation.namespace(), tlsAuth.getCertificateAndKey().getSecretName(), tlsAuth.getCertificateAndKey().getCertificate());
                String keyConfigProviderValue = String.format(PLACEHOLDER_SECRET_TEMPLATE_KUBE_CONFIG_PROVIDER, reconciliation.namespace(), tlsAuth.getCertificateAndKey().getSecretName(), tlsAuth.getCertificateAndKey().getKey());

                writer.println("ssl.keystore.certificate.chain=" + certConfigProviderValue);
                writer.println("ssl.keystore.key=" + keyConfigProviderValue);
                writer.println("ssl.keystore.type=PEM");

                writer.println("producer.ssl.keystore.certificate.chain=" + certConfigProviderValue);
                writer.println("producer.ssl.keystore.key=" + keyConfigProviderValue);
                writer.println("producer.ssl.keystore.type=PEM");

                writer.println("consumer.ssl.keystore.certificate.chain=" + certConfigProviderValue);
                writer.println("consumer.ssl.keystore.key=" + keyConfigProviderValue);
                writer.println("consumer.ssl.keystore.type=PEM");

                writer.println("admin.ssl.keystore.certificate.chain=" + certConfigProviderValue);
                writer.println("admin.ssl.keystore.key=" + keyConfigProviderValue);
                writer.println("admin.ssl.keystore.type=PEM");
                // otherwise SASL or OAuth is going to be used for authentication
            } else {
                securityProtocol = securityProtocol.equals("SSL") ? "SASL_SSL" : "SASL_PLAINTEXT";
                String saslMechanism = null;
                StringBuilder jaasConfig = new StringBuilder();
                String oauthCallbackClass = "";
                String producerOauthCallbackClass = "";
                String consumerOauthCallbackClass = "";
                String adminOauthCallbackClass = "";

                if (authentication instanceof KafkaClientAuthenticationPlain passwordAuth) {
                    saslMechanism = "PLAIN";
                    jaasConfig.append("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + passwordAuth.getUsername() + "\" password=\"" + formatPasswordTemplate(passwordAuth.getPasswordSecret(), PASSWORD_VOLUME_MOUNT) + "\";");
                } else if (authentication instanceof KafkaClientAuthenticationScram scramAuth) {
                    if (scramAuth.getType().equals(KafkaClientAuthenticationScramSha256.TYPE_SCRAM_SHA_256)) {
                        saslMechanism = "SCRAM-SHA-256";
                    } else if (scramAuth.getType().equals(KafkaClientAuthenticationScramSha512.TYPE_SCRAM_SHA_512)) {
                        saslMechanism = "SCRAM-SHA-512";
                    }
                    jaasConfig.append("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" + scramAuth.getUsername() + "\" password=\"" + formatPasswordTemplate(scramAuth.getPasswordSecret(), PASSWORD_VOLUME_MOUNT) + "\";");
                } else if (authentication instanceof KafkaClientAuthenticationOAuth oauth) {
                    saslMechanism = "OAUTHBEARER";
                    String oauthConfig = AuthenticationUtils.oauthJaasOptions(oauth).entrySet().stream()
                            .map(e -> e.getKey() + "=\"" + e.getValue() + "\"")
                            .collect(Collectors.joining(" "));

                    if (!oauthConfig.isEmpty()) {
                        jaasConfig.append("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required " + oauthConfig);
                    } else {
                        jaasConfig.append("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required");
                    }

                    if (oauth.getClientSecret() != null) {
                        jaasConfig.append(" oauth.client.secret=\"" + formatOauthSecretTemplate(oauth.getClientSecret()) + "\"");
                    }

                    if (oauth.getRefreshToken() != null) {
                        jaasConfig.append(" oauth.refresh.token=\"" + formatOauthSecretTemplate(oauth.getRefreshToken()) + "\"");
                    }

                    if (oauth.getAccessToken() != null) {
                        jaasConfig.append(" oauth.access.token=\"" + formatOauthSecretTemplate(oauth.getAccessToken()) + "\"");
                    }

                    if (oauth.getPasswordSecret() != null) {
                        jaasConfig.append(" oauth.password.grant.password=\"" + formatPasswordTemplate(oauth.getPasswordSecret(), OAUTH_SECRETS_BASE_VOLUME_MOUNT) + "\"");
                    }

                    if (oauth.getClientAssertion() != null) {
                        jaasConfig.append(" oauth.client.assertion=\"" + formatOauthSecretTemplate(oauth.getClientAssertion()) + "\"");
                    }

                    if (oauth.getTlsTrustedCertificates() != null && !oauth.getTlsTrustedCertificates().isEmpty()) {
                        String oauthTrustedCertsSecret = KafkaConnectResources.internalOauthTrustedCertsSecretName(clusterName);
                        String trustStorePath = OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT + oauthTrustedCertsSecret + "/" + oauthTrustedCertsSecret + ".crt";
                        jaasConfig.append(" oauth.ssl.truststore.location=\"" + trustStorePath + "\" oauth.ssl.truststore.type=\"PEM\"");
                    }

                    jaasConfig.append(";");
                    oauthCallbackClass = "sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler";
                    producerOauthCallbackClass = "producer." + oauthCallbackClass;
                    consumerOauthCallbackClass = "consumer." + oauthCallbackClass;
                    adminOauthCallbackClass = "admin." + oauthCallbackClass;

                }
                writer.println("sasl.mechanism=" + saslMechanism);
                writer.println("sasl.jaas.config=" + jaasConfig);
                writer.println(oauthCallbackClass);

                writer.println("producer.sasl.mechanism=" + saslMechanism);
                writer.println("producer.sasl.jaas.config=" + jaasConfig);
                writer.println(producerOauthCallbackClass);

                writer.println("consumer.sasl.mechanism=" + saslMechanism);
                writer.println("consumer.sasl.jaas.config=" + jaasConfig);
                writer.println(consumerOauthCallbackClass);

                writer.println("admin.sasl.mechanism=" + saslMechanism);
                writer.println("admin.sasl.jaas.config=" + jaasConfig);
                writer.println(adminOauthCallbackClass);

                writer.println();
            }
        }
        return this;
    }

    private String formatOauthSecretTemplate(GenericSecretSource secret) {
        return String.format(PLACEHOLDER_VOLUME_MOUNTED_SECRET_TEMPLATE_CONFIG_PROVIDER_DIR, OAUTH_SECRETS_BASE_VOLUME_MOUNT, secret.getSecretName(), secret.getKey());
    }

    private String formatPasswordTemplate(PasswordSecretSource secret, String volumeMountPath) {
        return String.format(PLACEHOLDER_VOLUME_MOUNTED_SECRET_TEMPLATE_CONFIG_PROVIDER_DIR, volumeMountPath, secret.getSecretName(), secret.getPassword());
    }

    /**
     * Adds consumer client rack {@code rack.id}. The rack ID will be set in the container based on the value of the
     * rack.id file (if it exists). This file is generated by the init-container used when rack awareness is enabled.
     *
     * @return Returns the builder instance
     */
    public KafkaConnectConfigurationBuilder withRackId()   {
        printSectionHeader("Additional information");
        writer.println("consumer.client.rack=${strimzidir:/opt/kafka/init:rack.id}");
        writer.println();
        return this;
    }

    /**
     * Configures the Kafka Connect configuration providers
     *
     * @param userConfig    the user configuration to extract the possible user-provided config provider configuration from it
     */
    private void printConfigProviders(AbstractConfiguration userConfig) {
        printSectionHeader("Config providers");
        String strimziConfigProviders = "strimzienv,strimzifile,strimzidir,strimzisecrets";

        if (userConfig != null && !userConfig.getConfiguration().isEmpty() && userConfig.getConfigOption("config.providers") != null) {
            writer.println("# Configuration providers configured by the user and by Strimzi");
            writer.println("config.providers=" + userConfig.getConfigOption("config.providers") + "," + strimziConfigProviders);
            userConfig.removeConfigOption("config.providers");
        } else {
            writer.println("# Configuration providers configured by Strimzi");
            writer.println("config.providers=" + strimziConfigProviders);
        }

        writer.println("config.providers.strimzienv.class=org.apache.kafka.common.config.provider.EnvVarConfigProvider");
        writer.println("config.providers.strimzienv.param.allowlist.pattern=.*");
        writer.println("config.providers.strimzifile.class=org.apache.kafka.common.config.provider.FileConfigProvider");
        writer.println("config.providers.strimzidir.class=org.apache.kafka.common.config.provider.DirectoryConfigProvider");
        writer.println("config.providers.strimzidir.param.allowed.paths=/opt/kafka");
        writer.println("config.providers.strimzisecrets.class=io.strimzi.kafka.KubernetesSecretConfigProvider");
        writer.println();
    }

    /**
     * Adds user provided Kafka Connect configurations.
     *
     * @param configurations   User provided Kafka Connect configurations
     * @param injectKafkaJmxReporter         Flag to indicate if metrics are enabled. If they are we inject the JmxReporter into the configuration
     * @param injectStrimziMetricsReporter   Inject the Strimzi Metrics Reporter into the configuration
     * @return Returns the builder instance
     */
    public KafkaConnectConfigurationBuilder withUserConfiguration(AbstractConfiguration configurations,
                                                                  boolean injectKafkaJmxReporter,
                                                                  boolean injectStrimziMetricsReporter) {
        // creating a defensive copy to avoid mutating the input config
        if (configurations instanceof KafkaConnectConfiguration kcConfiguration) {
            configurations = new KafkaConnectConfiguration(kcConfiguration);
        } else if (configurations instanceof KafkaMirrorMaker2Configuration kmm2Configuration) {
            configurations = new KafkaMirrorMaker2Configuration(kmm2Configuration);
        } else {
            // empty user configuration
            configurations = new KafkaConnectConfiguration(reconciliation, new ArrayList<>());
        }

        printConfigProviders(configurations);

        printMetricReportersForKey("metric.reporters", configurations, injectKafkaJmxReporter, injectStrimziMetricsReporter);
        printMetricReportersForKey("admin.metric.reporters", configurations, injectKafkaJmxReporter, injectStrimziMetricsReporter);
        printMetricReportersForKey("producer.metric.reporters", configurations, injectKafkaJmxReporter, injectStrimziMetricsReporter);
        printMetricReportersForKey("consumer.metric.reporters", configurations, injectKafkaJmxReporter, injectStrimziMetricsReporter);

        if (!configurations.getConfiguration().isEmpty()) {
            printSectionHeader("User provided configuration");
            writer.println(configurations.getConfiguration());
            writer.println();
        }
        return this;
    }

    private void printMetricReportersForKey(String configKey,
                                            AbstractConfiguration userConfig,
                                            boolean injectKafkaJmxReporter,
                                            boolean injectStrimziMetricsReporter) {
        // Build a list of reporters to inject based on flags
        List<String> reportersToInject = new ArrayList<>();
        // JmxPrometheusExporter depends on JmxReporter, which needs to be explicitly added when having custom metrics reporters
        if (injectKafkaJmxReporter) reportersToInject.add("org.apache.kafka.common.metrics.JmxReporter");
        if (injectStrimziMetricsReporter) reportersToInject.add(StrimziMetricsReporterConfig.KAFKA_CLASS);

        if (!reportersToInject.isEmpty()) {
            if (userConfig != null && !userConfig.getConfiguration().isEmpty() &&
                    userConfig.getConfigOption(configKey) != null) {
                // handle user configuration if present and avoids duplicates
                String configValue = userConfig.getConfigOption(configKey);

                reportersToInject = reportersToInject.stream()
                        .filter(r -> !userConfig.getConfigOption(configKey).contains(r))
                        .toList();

                if (!reportersToInject.isEmpty()) {
                    userConfig.removeConfigOption(configKey);

                    printSectionHeader(configKey + " configuration");
                    writer.println("# " + configKey + " configured by the user and by Strimzi");
                    writer.println(configKey + "=" + configValue + "," + String.join(",", reportersToInject));
                    writer.println();
                }
            } else {
                printSectionHeader(configKey + " configuration");
                writer.println("# " + configKey + " configured by Strimzi");
                writer.println(configKey + "=" + String.join(",", reportersToInject));
                writer.println();
            }
        }
    }

    /**
     * Configures the rest API listener.
     *
     * @param port Rest API port
     * @return Returns the builder instance
     */
    public KafkaConnectConfigurationBuilder withRestListeners(int port)  {
        printSectionHeader("REST Listeners");
        writer.println("rest.advertised.host.name=${strimzienv:ADVERTISED_HOSTNAME}");
        writer.println("rest.advertised.port=" + port);
        writer.println();

        return this;
    }

    /**
     * Configures plugins.
     *
     * @return Returns the builder instance
     */
    public KafkaConnectConfigurationBuilder withPluginPath() {
        printSectionHeader("Plugins");
        writer.println("plugin.path=/opt/kafka/plugins");
        writer.println();

        return  this;
    }

    /**
     * Configures the Strimzi Metrics Reporter. It is set only if user enables Strimzi Metrics Reporter.
     *
     * @param model Strimzi Metrics Reporter configuration
     *
     * @return Returns the builder instance
     */
    public KafkaConnectConfigurationBuilder withStrimziMetricsReporter(MetricsModel model) {
        if (model instanceof StrimziMetricsReporterModel reporterModel) {
            printSectionHeader("Strimzi Metrics Reporter configuration");
            writer.println(StrimziMetricsReporterConfig.LISTENER_ENABLE + "=true");
            writer.println(StrimziMetricsReporterConfig.LISTENER + "=http://:" + MetricsModel.METRICS_PORT);
            writer.println(StrimziMetricsReporterConfig.ALLOW_LIST + "=" + reporterModel.getAllowList());
            writer.println("admin." + StrimziMetricsReporterConfig.LISTENER_ENABLE + "=true");
            writer.println("admin." + StrimziMetricsReporterConfig.LISTENER + "=http://:" + MetricsModel.METRICS_PORT);
            writer.println("admin." + StrimziMetricsReporterConfig.ALLOW_LIST + "=" + reporterModel.getAllowList());
            writer.println("producer." + StrimziMetricsReporterConfig.LISTENER_ENABLE + "=true");
            writer.println("producer." + StrimziMetricsReporterConfig.LISTENER + "=http://:" + MetricsModel.METRICS_PORT);
            writer.println("producer." + StrimziMetricsReporterConfig.ALLOW_LIST + "=" + reporterModel.getAllowList());
            writer.println("consumer." + StrimziMetricsReporterConfig.LISTENER_ENABLE + "=true");
            writer.println("consumer." + StrimziMetricsReporterConfig.LISTENER + "=http://:" + MetricsModel.METRICS_PORT);
            writer.println("consumer." + StrimziMetricsReporterConfig.ALLOW_LIST + "=" + reporterModel.getAllowList());
            writer.println();
        }
        return this;
    }

    /**
     * Prints the section header into the configuration file. This makes it more human-readable
     * when looking for issues in running pods etc.
     *
     * @param sectionName   Name of the section for which is this header printed
     */
    private void printSectionHeader(String sectionName)   {
        writer.println("##########");
        writer.println("# " + sectionName);
        writer.println("##########");
    }

    /**
     * Prints the file header which is at the beginning of the configuration file.
     */
    private void printHeader()   {
        writer.println("##############################");
        writer.println("##############################");
        writer.println("# This file is automatically generated by the Strimzi Cluster Operator");
        writer.println("# Any changes to this file will be ignored and overwritten!");
        writer.println("##############################");
        writer.println("##############################");
        writer.println();
    }

    /**
     * Generates the configuration template as String
     *
     * @return String with the Kafka connect configuration template
     */
    public String build()  {
        configureSecurityProtocol();
        return stringWriter.toString();
    }
}
