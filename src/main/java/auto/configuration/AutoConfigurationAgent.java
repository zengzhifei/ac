package auto.configuration;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @author zengzhifei
 * 2022/6/7 11:29
 */
public class AutoConfigurationAgent {
    public static void premain(String args, Instrumentation inst) {
    }

    public static void agentmain(String args, Instrumentation inst) {
        log("AutoConfigurationAgent agentmain start...");
        Map<String, Map<String, String>> paramsMap = parseArgs(args);
        for (Map.Entry<String, Map<String, String>> entry : paramsMap.entrySet()) {
            if (enable(entry.getValue())) {
                switch (entry.getKey()) {
                    case "logger":
                        new LoggerLevelTransformer(entry.getValue(), inst).config();
                        break;
                    default:
                        break;
                }
            }
        }
        log("AutoConfigurationAgent agentmain end...");
    }

    public static boolean enable(Map<String, String> property) {
        return "true".equalsIgnoreCase(property.getOrDefault("enable", "false"));
    }

    public static void log(String format, Object... args) {
        String message = String.format(format, args);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String datetime = sdf.format(new Date());
        System.out.println(datetime + " " + message);
    }

    private static Map<String, Map<String, String>> parseArgs(String args) {
        final Map<String, Map<String, String>> paramsMap = new HashMap<>(4);

        try {
            if (args != null && args.length() > 0) {
                // logger:a=1&b=2
                String[] arguments = args.split("\n");
                for (String argument : arguments) {
                    String[] params = argument.split(":");
                    if (params.length == 2) {
                        Map<String, String> propertyMap = paramsMap.getOrDefault(params[0], new HashMap<>(8));
                        String[] keyValues = params[1].split(",");
                        for (String keyValue : keyValues) {
                            String[] kv = keyValue.split("=");
                            if (kv.length == 2) {
                                propertyMap.put(kv[0], kv[1]);
                            }
                        }
                        paramsMap.put(params[0], propertyMap);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return paramsMap;
    }

    private static class LoggerLevelTransformer {
        private final Set<String> LEVELS = new HashSet<>(Arrays.asList("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"));
        private final Map<String, String> LOGGER_LEVEL = new HashMap<>();
        private LoggerType loggerType = null;
        private ClassLoader classLoader = this.getClass().getClassLoader();

        public LoggerLevelTransformer(Map<String, String> property, Instrumentation inst) {
            log("LoggerLevelTransformer is running...");

            try {
                for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
                    if ("org.slf4j.impl.StaticLoggerBinder".equals(loadedClass.getName())) {
                        classLoader = loadedClass.getClassLoader();
                        String file = loadedClass.getProtectionDomain().getCodeSource().getLocation().getFile();
                        if (file.contains(LoggerType.LOGBACK.flag)) {
                            loggerType = LoggerType.LOGBACK;
                            break;
                        } else if (file.contains(LoggerType.LOG4J2.flag)) {
                            loggerType = LoggerType.LOG4J2;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                String acPath = property.getOrDefault("ac.path", System.getProperty("user.dir"));
                String globalLevel = property.getOrDefault("level", null);
                String loggerFile = acPath + "/logger.ac";
                FileInputStream inputStream = new FileInputStream(loggerFile);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String logger;
                while ((logger = bufferedReader.readLine()) != null && !logger.isEmpty()) {
                    String[] loggerLevel = logger.split(":");
                    String level = globalLevel != null && LEVELS.contains(globalLevel.toUpperCase()) ? globalLevel : (loggerLevel.length > 1 ? loggerLevel[1] : null);
                    LOGGER_LEVEL.put(loggerLevel[0], LEVELS.contains(level) ? level : "INFO");
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                log("logger level: %s", LOGGER_LEVEL);
            }
        }

        public void config() {
            if (loggerType == null) {
                log("logger type is not support");
                return;
            } else {
                log("logger type: %s", loggerType.name());
            }

            for (Map.Entry<String, String> entry : LOGGER_LEVEL.entrySet()) {
                String loggerName = entry.getKey();
                String loggerLevel = entry.getValue();
                log("transform config: %s", entry);

                if (LoggerType.LOGBACK.equals(loggerType)) {
                    try {
                        Class<?> clazz = Class.forName("org.slf4j.LoggerFactory", false, classLoader);
                        Method method = clazz.getDeclaredMethod("getILoggerFactory");
                        Object context = method.invoke(clazz);

                        Class<?> contextClass = Class.forName("ch.qos.logback.classic.LoggerContext", false,
                                classLoader);
                        Method getLoggerMethod = contextClass.getDeclaredMethod("getLogger", String.class);
                        Object logger = getLoggerMethod.invoke(context, loggerName);

                        Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level", false, classLoader);
                        Method toLevelMethod = levelClass.getDeclaredMethod("toLevel", String.class);
                        Object level = toLevelMethod.invoke(levelClass, loggerLevel);

                        Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger", false, classLoader);
                        Method setLevelMethod = loggerClass.getDeclaredMethod("setLevel", levelClass);
                        setLevelMethod.invoke(logger, level);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (LoggerType.LOG4J2.equals(loggerType)) {
                    try {
                        Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level", false, classLoader);
                        Method toLevelMethod = levelClass.getDeclaredMethod("toLevel", String.class);
                        Object level = toLevelMethod.invoke(levelClass, loggerLevel);

                        Class<?> clazz = Class.forName("org.apache.logging.log4j.core.config.Configurator",
                                false, classLoader);
                        Method setLevelMethod = clazz.getDeclaredMethod("setLevel", String.class, levelClass);
                        setLevelMethod.invoke(clazz, loggerName, level);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private enum LoggerType {
        LOGBACK("logback-classic"), LOG4J2("log4j-slf4j-impl");

        private final String flag;

        LoggerType(String flag) {
            this.flag = flag;
        }
    }
}
