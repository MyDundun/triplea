package org.triplea.dropwizard.common;

import es.moki.ratelimij.dropwizard.RateLimitBundle;
import es.moki.ratelimitj.inmemory.InMemoryRateLimiterFactory;
import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.jdbi3.bundles.JdbiExceptionsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.experimental.UtilityClass;
import org.jdbi.v3.core.Jdbi;

@UtilityClass
public class DropWizardServer {

  private static final String[] DEFAULT_ARGS = new String[] {"server", "configuration.yml"};

  public <T extends Configuration> void startServer(
      final Application<T> application, final String[] cliArgs) throws Exception {
    // if no args are provided then we will use default values.
    application.run(cliArgs.length == 0 ? DEFAULT_ARGS : cliArgs);
  }

  /**
   * This bootstrap will replace ${...} values in YML configuration with environment variable
   * values. Without it, all values in the YML configuration are treated as literals.
   */
  public <T extends Configuration> void enableConfigurationEnvironmentVariableSubstitution(
      final Bootstrap<T> bootstrap) {
    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(), new EnvironmentVariableSubstitutor(false)));
  }

  public <T extends Configuration> void unwrapJdbiSqlExceptionsForBetterStackTraces(
      final Bootstrap<T> bootstrap) {
    // From: https://www.dropwizard.io/0.7.1/docs/manual/jdbi.html
    // By adding the JdbiExceptionsBundle to your application, Dropwizard will automatically unwrap
    // ant thrown SQLException or DBIException instances. This is critical for debugging, since
    // otherwise only the common wrapper exception’s stack trace is logged.
    bootstrap.addBundle(new JdbiExceptionsBundle());
  }

  public <T extends Configuration> void enableRateLimiting(
      final Bootstrap<T> bootstrap) {
    bootstrap.addBundle(new RateLimitBundle(new InMemoryRateLimiterFactory()));
  }


  public  Jdbi createJdbi(final AppConfig configuration, final Environment environment) {
    final JdbiFactory factory = new JdbiFactory();
    final Jdbi jdbi =
        factory.build(environment, configuration.getDatabase(), "postgresql-connection-pool");
    JdbiDatabase.registerRowMappers(jdbi);

    if (configuration.isLogSqlStatements()) {
      JdbiDatabase.registerSqlLogger(jdbi);
    }
    return jdbi;
  }
}
