/**
 * Copyright 2015 Cerner Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.apps.spi.jobs;

import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.kitesdk.apps.AppContext;
import org.kitesdk.apps.AppException;
import org.kitesdk.apps.scheduled.DataIn;
import org.kitesdk.apps.scheduled.DataOut;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Utility functions work working with jobs.
 */
public class JobUtil {

  /**
   *
   * @param jobName
   * @param context
   * @return
   */
  public static Map<String,String> toJobSettings(String jobName, AppContext context) {

    // TODO: implement.
    return context.getSettings();
  }

  public static Configuration toJobHadoopConf(String jobNAme, AppContext context) {

    // TODO: implement.
    return context.getHadoopConf();
  }

  public static Method resolveRunMethod(Object job) {

    Method runMethod = null;

    for (Method method: job.getClass().getMethods()) {

      if ("run".equals(method.getName())) {

        if (runMethod != null)
          throw new AppException("Multiple run methods found on scheduled job class "
              + job.getClass().getName());

        runMethod = method;
      }
    }

    if (runMethod == null)
      throw new AppException("Could not find run method on job class "  +
          job.getClass().getName());

    return runMethod;
  }

  public static Map<String, DataIn> getInputs(Method runMethod) {

    Annotation[][] paramAnnotations = runMethod.getParameterAnnotations();

    Map<String,DataIn> inputs = Maps.newHashMap();

    for (Annotation[] annotations: paramAnnotations) {
      for (Annotation annotation: annotations) {
        if (DataIn.class.equals(annotation.annotationType()))
          inputs.put(((DataIn) annotation).name(), (DataIn) annotation);
      }
    }

    return inputs;
  }

  public static Map<String, DataOut> getOutputs(Method runMethod) {

    Annotation[][] paramAnnotations = runMethod.getParameterAnnotations();

    Map<String,DataOut> outputs = Maps.newHashMap();

    for (Annotation[] annotations: paramAnnotations) {
      for (Annotation annotation: annotations) {
        if (DataOut.class.equals(annotation.annotationType()))
          outputs.put(((DataOut) annotation).name(), (DataOut) annotation);
      }
    }

    return outputs;
  }

  /**
   * Gets a map of named parameters to their types.
   */
  public static Map<String,Class> getTypes(Method runMethod) {

    Annotation[][] paramAnnotations = runMethod.getParameterAnnotations();

    Class<?>[] types = runMethod.getParameterTypes();

    Map<String,Class> outputs = Maps.newHashMap();

    for (int i = 0; i < paramAnnotations.length; ++i) {

      Annotation[] annotations = paramAnnotations[i];

      for (Annotation annotation: annotations) {

        if (DataOut.class.equals(annotation.annotationType()) ||
            DataIn.class.equals(annotation.annotationType())) {

          String name = DataOut.class.equals(annotation.annotationType()) ?
              ((DataOut) annotation).name() :
              ((DataIn) annotation).name();

          outputs.put(name, types[i]);
        }
      }
    }

    return outputs;
  }

  /**
   * Returns the arguments to be passed to a job's run method.
   */
  public static Object[] getArgs(Method runMethod, Map<String,?> namedParams) {

    Annotation[][] paramAnnotations = runMethod.getParameterAnnotations();
    Object[] args = new Object[paramAnnotations.length];

    for (int i = 0; i < paramAnnotations.length; ++i) {

      Annotation[] annotations = paramAnnotations[i];

      for (Annotation annotation: annotations) {
        if (DataOut.class.equals(annotation.annotationType())) {

          Object output = namedParams.get(((DataOut) annotation).name());

          if (output == null) {
            throw new AppException("No output defined for job parameter: ");
          }

          args[i] = output;
        }

        if (DataIn.class.equals(annotation.annotationType())) {

          Object input = namedParams.get(((DataIn) annotation).name());

          if (input == null) {
            throw new AppException("No input defined for job parameter: ");
          }

          args[i] = input;
        }
      }
    }

    return args;
  }
}
