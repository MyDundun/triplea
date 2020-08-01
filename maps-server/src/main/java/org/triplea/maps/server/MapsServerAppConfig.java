package org.triplea.maps.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * This configuration class is injected with properties from the server YML configuration. We also
 * store here any other static configuration properties, all configuration values should essentially
 * live here and be obtained from this class. An instance of this class is created by DropWizard on
 * launch and then is passed to the application class.
 */
public class MapsServerAppConfig extends Configuration {
  @Getter(onMethod_ = {@JsonProperty})
  @Setter(onMethod_ = {@JsonProperty})
  private boolean logRequestAndResponses;

  @Getter(onMethod_ = {@JsonProperty})
  @Setter(onMethod_ = {@JsonProperty})
  private boolean logSqlStatements;

  @Valid @NotNull @JsonProperty @Getter
  private final DataSourceFactory database = new DataSourceFactory();
}
