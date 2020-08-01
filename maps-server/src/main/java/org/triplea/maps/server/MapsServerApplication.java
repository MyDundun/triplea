package org.triplea.maps.server;

import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import es.moki.ratelimij.dropwizard.RateLimitBundle;
import es.moki.ratelimitj.inmemory.InMemoryRateLimiterFactory;
import io.dropwizard.Application;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.CachingAuthenticator;
import io.dropwizard.auth.oauth.OAuthCredentialAuthFilter;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.jdbi3.bundles.JdbiExceptionsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.websockets.WebsocketBundle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpointConfig;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.jdbi.v3.core.Jdbi;
import org.triplea.db.JdbiDatabase;
import org.triplea.http.client.AuthenticationHeaders;
import org.triplea.http.client.web.socket.WebsocketPaths;

/**
 * Main entry-point for launching drop wizard HTTP server. This class is responsible for configuring
 * any Jersey plugins, registering resources (controllers) and injecting those resources with
 * configuration properties from 'AppConfig'.
 */
public class MapsServerApplication extends Application<MapsServerAppConfig> {

  private static final String[] DEFAULT_ARGS = new String[] {"server", "configuration.yml"};

  /**
   * Main entry-point method, launches the drop-wizard http server. If no args are passed then will
   * use default values suitable for local development.
   */
  public static void main(final String[] args) throws Exception {
    final MapsServerApplication application = new MapsServerApplication();
    // if no args are provided then we will use default values.
    application.run(args.length == 0 ? DEFAULT_ARGS : args);
  }

  @Override
  public void initialize(final Bootstrap<MapsServerAppConfig> bootstrap) {
    // This bootstrap will replace ${...} values in YML configuration with environment
    // variable values. Without it, all values in the YML configuration are treated as literals.
    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(), new EnvironmentVariableSubstitutor(false)));

    // From: https://www.dropwizard.io/0.7.1/docs/manual/jdbi.html
    // By adding the JdbiExceptionsBundle to your application, Dropwizard will automatically unwrap
    // ant thrown SQLException or DBIException instances. This is critical for debugging, since
    // otherwise only the common wrapper exception’s stack trace is logged.
    bootstrap.addBundle(new JdbiExceptionsBundle());
    bootstrap.addBundle(new RateLimitBundle(new InMemoryRateLimiterFactory()));

    bootstrap.addBundle(new WebsocketBundle(gameConnectionWebsocket, playerConnectionWebsocket));
  }

  @Override
  public void run(final AppConfig configuration, final Environment environment) {
    if (configuration.isLogRequestAndResponses()) {
      enableRequestResponseLogging(environment);
    }

    final MetricRegistry metrics = new MetricRegistry();
    final Jdbi jdbi = createJdbi(configuration, environment);

    environment.jersey().register(BannedPlayerFilter.newBannedPlayerFilter(jdbi));
    environment.jersey().register(new RolesAllowedDynamicFeature());
    enableAuthentication(environment, metrics, jdbi);

    exceptionMappers().forEach(mapper -> environment.jersey().register(mapper));

    final var sessionIsBannedCheck = SessionBannedCheck.build(jdbi);
    final var gameConnectionMessagingBus = new WebSocketMessagingBus();
    setupWebSocket(gameConnectionWebsocket, gameConnectionMessagingBus, sessionIsBannedCheck);

    final var playerConnectionMessagingBus = new WebSocketMessagingBus();
    setupWebSocket(playerConnectionWebsocket, playerConnectionMessagingBus, sessionIsBannedCheck);

    final var chatters = Chatters.build();
    ChatMessagingService.build(chatters, jdbi).configure(playerConnectionMessagingBus);

    endPointControllers(
            configuration, jdbi, chatters, playerConnectionMessagingBus, gameConnectionMessagingBus)
        .forEach(controller -> environment.jersey().register(controller));
  }

  private static void enableRequestResponseLogging(final Environment environment) {
    environment
        .jersey()
        .register(
            new LoggingFeature(
                Logger.getLogger(LoggingFeature.DEFAULT_LOGGER_NAME),
                Level.INFO,
                LoggingFeature.Verbosity.PAYLOAD_ANY,
                LoggingFeature.DEFAULT_MAX_ENTITY_SIZE));
  }

  private Jdbi createJdbi(final AppConfig configuration, final Environment environment) {
    final JdbiFactory factory = new JdbiFactory();
    final Jdbi jdbi =
        factory.build(environment, configuration.getDatabase(), "postgresql-connection-pool");
    JdbiDatabase.registerRowMappers(jdbi);

    if (configuration.isLogSqlStatements()) {
      JdbiDatabase.registerSqlLogger(jdbi);
    }
    return jdbi;
  }

  private static void enableAuthentication(
      final Environment environment, final MetricRegistry metrics, final Jdbi jdbi) {
    environment
        .jersey()
        .register(
            new AuthDynamicFeature(
                new OAuthCredentialAuthFilter.Builder<AuthenticatedUser>()
                    .setAuthenticator(buildAuthenticator(metrics, jdbi))
                    .setAuthorizer(new RoleAuthorizer())
                    .setPrefix(AuthenticationHeaders.KEY_BEARER_PREFIX)
                    .buildAuthFilter()));
    environment.jersey().register(new AuthValueFactoryProvider.Binder<>(AuthenticatedUser.class));
  }

  private static CachingAuthenticator<String, AuthenticatedUser> buildAuthenticator(
      final MetricRegistry metrics, final Jdbi jdbi) {
    return new CachingAuthenticator<>(
        metrics,
        ApiKeyAuthenticator.build(jdbi),
        CacheBuilder.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(10000));
  }

  private List<Object> exceptionMappers() {
    return ImmutableList.of(new IllegalArgumentMapper());
  }

  private static void setupWebSocket(
      final ServerEndpointConfig websocket,
      final WebSocketMessagingBus webSocketMessagingBus,
      final Predicate<Session> sessionBanCheck) {

    // Inject beans into websocket endpoints
    websocket
        .getUserProperties()
        .putAll(
            Map.of(
                WebSocketMessagingBus.MESSAGING_BUS_KEY, //
                webSocketMessagingBus,
                SessionBannedCheck.BAN_CHECK_KEY,
                sessionBanCheck));
  }

  private List<Object> endPointControllers(
      final AppConfig appConfig,
      final Jdbi jdbi,
      final Chatters chatters,
      final WebSocketMessagingBus playerMessagingBus,
      final WebSocketMessagingBus gameMessagingBus) {
    final GameListing gameListing = GameListing.build(jdbi, playerMessagingBus);
    return ImmutableList.of(
        AccessLogController.build(jdbi),
        BadWordsController.build(jdbi),
        CreateAccountController.build(jdbi),
        DisconnectUserController.build(jdbi, chatters, playerMessagingBus),
        ForgotPasswordController.build(appConfig, jdbi),
        GameChatHistoryController.build(jdbi),
        GameHostingController.build(jdbi),
        GameListingController.build(gameListing),
        LobbyWatcherController.build(appConfig, jdbi, gameListing),
        LoginController.build(jdbi, chatters),
        UsernameBanController.build(jdbi),
        UserBanController.build(jdbi, chatters, playerMessagingBus, gameMessagingBus),
        ErrorReportController.build(appConfig, jdbi),
        ModeratorAuditHistoryController.build(jdbi),
        ModeratorsController.build(jdbi),
        MuteUserController.build(chatters),
        PlayerInfoController.build(jdbi, chatters, gameListing),
        PlayersInGameController.build(gameListing),
        RemoteActionsController.build(jdbi, gameMessagingBus),
        UpdateAccountController.build(jdbi));
  }
}
