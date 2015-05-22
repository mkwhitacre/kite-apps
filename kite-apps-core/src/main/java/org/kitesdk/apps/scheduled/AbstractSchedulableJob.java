package org.kitesdk.apps.scheduled;

import org.apache.hadoop.conf.Configuration;
import org.joda.time.Instant;

/**
 * Abstract base class for schedulable jobs.
 */
public abstract class AbstractSchedulableJob implements SchedulableJob {

  private Configuration configuration;

  private Instant nominalTime;

  @Override
  public String getName() {

    // Use the simple class name to identify the job.
    return getClass().getSimpleName();
  }

  @Override
  public void setConf(Configuration configuration) {
    this.configuration = configuration;
  }

  @Override
  public Configuration getConf() {
    return configuration;
  }

  @Override
  public Instant getNominalTime() {
    return nominalTime;
  }

  @Override
  public void setNominalTime(Instant nominalTime) {
    this.nominalTime = nominalTime;
  }
}