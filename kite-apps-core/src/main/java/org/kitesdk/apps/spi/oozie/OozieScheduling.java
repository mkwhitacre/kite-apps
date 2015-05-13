package org.kitesdk.apps.spi.oozie;

import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.kitesdk.apps.scheduled.DataIn;
import org.kitesdk.apps.scheduled.DataOut;
import org.kitesdk.apps.scheduled.Schedule;

import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;
import org.codehaus.plexus.util.xml.XMLWriter;
import org.codehaus.plexus.util.xml.XmlStreamWriter;
import org.kitesdk.apps.spi.ScheduledJobUtil;
import org.kitesdk.data.Datasets;
import org.kitesdk.data.View;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;


/**
 * Support for creating and using Oozie workflows.
 */
public class OozieScheduling {

  private static final String WORKFLOW_ELEMENT = "workflow-app";

  private static final String COORDINATOR_ELEMENT = "coordinator-app";

  private static final String BUNDLE_ELEMENT = "bundle-app";

  private static final String OOZIE_COORD_NS = "uri:oozie:coordinator:0.4";

  private static final String OOZIE_WORKFLOW_NS = "uri:oozie:workflow:0.5";

  private static final String OOZIE_BUNDLE_NS = "uri:oozie:bundle:0.2";

  private static final DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm'Z'")
      .withZone(DateTimeZone.UTC);

  private static final String COORD_NOMINAL_TIME = "coordNominalTime";

  private static final String WORKFLOW_NOMINAL_TIME = "workflowNominalTime";
  private static final String HIVE_METASTORE_URIS = "hive.metastore.uris";

  public static Instant getNominalTime(Configuration conf) {

    String timeString = conf.get(WORKFLOW_NOMINAL_TIME);

    if (timeString == null) {
      return null;
    }

    return new Instant(formatter.parseMillis(timeString));
  }

  private static void element(XMLWriter writer, String name, String attributeName,
                              String attributeValue) {

    writer.startElement(name);
    writer.addAttribute(attributeName, attributeValue);
    writer.endElement();
  }

  private static void element(XMLWriter writer, String name, String text) {

    writer.startElement(name);
    writer.writeText(text);
    writer.endElement();
  }

  public static void writeWorkFlow(Schedule schedule,
                                   Configuration conf,
                                   OutputStream output) throws IOException {

    XmlStreamWriter streamWriter = WriterFactory.newXmlWriter(output);

    PrettyPrintXMLWriter writer = new PrettyPrintXMLWriter(streamWriter);

    String jobClassName = schedule.getJobClass().getCanonicalName();

    // Create a unique id for node names that is less than Oozie's 50
    // character limit
    String jobId = jobClassName.substring(jobClassName.lastIndexOf('.') + 1)
        + "_"  + jobClassName.hashCode(); // TODO: use stronger hash to avoid possible collision.

    writer.startElement(WORKFLOW_ELEMENT);
    writer.addAttribute("name", jobClassName.replace(".", "_"));
    writer.addAttribute("xmlns", OOZIE_WORKFLOW_NS);

    element(writer, "start", "to", jobId);

    writer.startElement("action");
    writer.addAttribute("name", jobId);

    // Reduce retry attempts. TODO: make this configurable?
    writer.addAttribute("retry-max", "2");
    writer.addAttribute("retry-interval", "1");

    writer.startElement("java");
    element(writer, "job-tracker", "${jobTracker}");
    element(writer, "name-node", "${nameNode}");

    // TODO: the job-xml should probably be job-specific configuration.
    // element(writer, "job-xml", "${appConfigPath}");

    // Make the nominal time visible to the workflow action.
    writer.startElement("configuration");
    property(writer, WORKFLOW_NOMINAL_TIME, "${" + COORD_NOMINAL_TIME + "}");

    // Write the Hive metastore URI, if available. It may be null
    // in local or testing environments.
    if (conf.get(HIVE_METASTORE_URIS) != null) {
      property(writer, HIVE_METASTORE_URIS, conf.get(HIVE_METASTORE_URIS));
    }

    // Include the dataset inputs to make them visible to the workflow.
    for (String name: schedule.getViewTemplates().keySet()) {

      property(writer, "wf_" + toIdentifier(name), "${coord_" + toIdentifier(name) + "}");
    }

    writer.endElement(); // configuration

    element(writer, "main-class", ScheduledJobMain.class.getCanonicalName());
    element(writer, "arg", jobClassName);

    writer.endElement(); // java
    element(writer, "ok", "to", "end");

    element(writer, "error", "to", "kill");

    writer.endElement(); // action

    writer.startElement("kill");
    writer.addAttribute("name", "kill");
    element(writer, "message", "Error in workflow for " + jobClassName);
    writer.endElement(); // kill

    element(writer, "end", "name", "end");

    writer.endElement(); // workflow
    streamWriter.flush();
  }

  private static final String toIdentifier(String name) {

    return name.replace(".", "_");
  }

  private static final void writeDatasets(XMLWriter writer, Schedule schedule) {

    writer.startElement("datasets");

    for (Map.Entry<String,Schedule.ViewTemplate> entry: schedule.getViewTemplates().entrySet()) {

      writer.startElement("dataset");
      writer.addAttribute("name", "ds_" + toIdentifier(entry.getKey()));
      writer.addAttribute("frequency", Integer.toString(entry.getValue().getFrequency()));
      writer.addAttribute("initial-instance", formatter.print(new Instant()));
      writer.addAttribute("timezone", "UTC");

      element(writer, "uri-template", URIShim.kiteToHDFS(entry.getValue().getUriTemplate()));

      // Don't create a done flag. This may be something we can remove when
      // when using a URI handler aware of Kite.
      element(writer, "done-flag", "");
      writer.endElement(); // dataset
    }

    writer.endElement(); // datasets

    Collection<DataIn> inputs = ScheduledJobUtil.getInputs(schedule.getJobClass()).values();

    if (!inputs.isEmpty()) {
      writer.startElement("input-events");

      for (DataIn input: inputs) {
        writer.startElement("data-in");
        writer.addAttribute("name", "datain_" + toIdentifier(input.name()));
        writer.addAttribute("dataset", "ds_" + toIdentifier(input.name()));
        element(writer, "instance", "${coord:current(0)}");
        writer.endElement(); // data-in
      }

      writer.endElement(); // input-events
    }

    Collection<DataOut> outputs = ScheduledJobUtil.getOutputs(schedule.getJobClass()).values();

    if (!outputs.isEmpty()) {
      writer.startElement("output-events");

      for (DataOut output: outputs) {
        writer.startElement("data-out");
        writer.addAttribute("name", "dataout_" + toIdentifier(output.name()));
        writer.addAttribute("dataset", "ds_" + toIdentifier(output.name()));
        element(writer, "instance", "${coord:current(0)}");
        writer.endElement(); // data-out
      }

      writer.endElement(); // output-events
    }
  }

  public static void writeCoordinator(Schedule schedule,
                                      Path workflowPath,
                                      OutputStream output) throws IOException {

    XmlStreamWriter streamWriter = WriterFactory.newXmlWriter(output);

    PrettyPrintXMLWriter writer = new PrettyPrintXMLWriter(streamWriter);

    String jobName = schedule.getJobClass().getCanonicalName();

    writer.startElement(COORDINATOR_ELEMENT);
    writer.addAttribute("name", jobName);
    writer.addAttribute("xmlns", OOZIE_COORD_NS);
    writer.addAttribute("frequency", schedule.getFrequency());
    writer.addAttribute("start", formatter.print(schedule.getStartTime()));

    writer.addAttribute("end", "3000-01-01T00:00Z");

    writer.addAttribute("timezone", "UTC");

    writeDatasets(writer, schedule);

    writer.startElement("action");

    writer.startElement("workflow");
    element(writer, "app-path", workflowPath.toString());

    writer.startElement("configuration");
    property(writer, COORD_NOMINAL_TIME, "${coord:nominalTime()}");

    // Include the dataset inputs to make them visible to the workflow.
    for (DataIn dataIn: ScheduledJobUtil.getInputs(schedule.getJobClass()).values()) {

      property(writer, "coord_" + toIdentifier(dataIn.name()),
          "${coord:dataIn('datain_" + toIdentifier(dataIn.name()) + "')}");
    }

    for (DataOut dataOut: ScheduledJobUtil.getOutputs(schedule.getJobClass()).values()) {

      property(writer, "coord_" + toIdentifier(dataOut.name()),
          "${coord:dataOut('dataout_" + toIdentifier(dataOut.name()) + "')}");
    }

    writer.endElement(); // configuration

    writer.endElement(); // workflow
    writer.endElement(); // action
    writer.endElement(); // coordinator
    streamWriter.flush();
  }

  /**
   * Writes a Hadoop-style configuration property.
   */
  private static void property(XMLWriter writer, String name, String value) {
    writer.startElement("property");

    element(writer, "name", name);
    element(writer, "value", value);

    writer.endElement();
  }

  public static void writeBundle(Class appClass,
                                 Configuration conf,
                                 @Nullable
                                 Path appConfigPath,
                                 Path libPath,
                                 List<Path> coordinatorPaths,
                                 OutputStream output) throws IOException {

    XmlStreamWriter streamWriter = WriterFactory.newXmlWriter(output);

    PrettyPrintXMLWriter writer = new PrettyPrintXMLWriter(streamWriter);

    writer.startElement(BUNDLE_ELEMENT);
    writer.addAttribute("name", appClass.getCanonicalName());
    writer.addAttribute("xmlns", OOZIE_BUNDLE_NS);

    writer.startElement("parameters");

    property(writer, "oozie.libpath", libPath.toString());
    property(writer, "nameNode", conf.get("fs.default.name"));

    String resourceManager = conf.get("yarn.resourcemanager.address");

    // MR2 uses YARN for the job tracker, but some Hadoop deployments
    // don't have the resoure manager setting visible. We work around this
    // by grabbing the job tracker setting and swapping to the resource
    // manager port.
    // TODO: is there a better way to deal with this?
    if (resourceManager == null) {

      String jobTracker = conf.get("mapred.job.tracker");

      resourceManager = jobTracker.replace("8021", "8032");
    }

    property(writer, "jobTracker", resourceManager);

    if (appConfigPath != null)
      property(writer, "appConfigPath", appConfigPath.toString());

    writer.endElement(); // parameters

    int i = 0;

    for (Path coordinatorPath: coordinatorPaths) {
      writer.startElement("coordinator");
      writer.addAttribute("name", "coord" + i++); // TODO: meaningful coordinator names?

      element(writer, "app-path", coordinatorPath.toString());
      writer.endElement(); // coordinator
    }

    writer.endElement(); // bundle
    streamWriter.flush();
  }

  public static Map<String,View> getViews(Class jobClass, Configuration conf) {

    Map<String,View> views = Maps.newHashMap();

    Collection<DataIn> inputs = ScheduledJobUtil.getInputs(jobClass).values();

    for (DataIn input: inputs) {

      String uri = conf.get("wf_" + OozieScheduling.toIdentifier(input.name()));

      String kiteURI = URIShim.HDFSToKite(uri);

      views.put(input.name(),
          Datasets.load(kiteURI, input.type()));
    }

    Collection<DataOut> outputs = ScheduledJobUtil.getOutputs(jobClass).values();

    for (DataOut output: outputs) {

      String uri = conf.get("wf_" + OozieScheduling.toIdentifier(output.name()));

      String kiteURI = URIShim.HDFSToKite(uri);

      views.put(output.name(),
          Datasets.load(kiteURI, output.type()));
    }

    return views;
  }

}