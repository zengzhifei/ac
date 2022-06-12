package auto.configuration;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.io.FileUtil;

/**
 * @author zengzhifei
 * 2022/6/7 11:29
 */
public class AutoConfigurationAgent {
    public static void premain(String args, Instrumentation inst) {
    }

    public static void agentmain(String args, Instrumentation inst) {
        System.out.println("AutoConfigurationAgent agentmain start...");
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
        System.out.println("AutoConfigurationAgent agentmain end...");
    }

    public static boolean enable(Map<String, String> property) {
        return "true".equalsIgnoreCase(property.getOrDefault("enable", "false"));
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
        private final Map<String, String> LOGGER_LEVEL = new HashMap<>();
        private LoggerType loggerType = null;
        private ClassLoader classLoader = this.getClass().getClassLoader();

        public LoggerLevelTransformer(Map<String, String> property, Instrumentation inst) {
            System.out.println("LoggerLevelTransformer is running...");

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
                String loggerFile = acPath + "/logger.ac";
                List<String> loggers = FileUtil.readLines(loggerFile, StandardCharsets.UTF_8);
                for (String logger : loggers) {
                    String[] loggerLevel = logger.split(":");
                    LOGGER_LEVEL.put(loggerLevel[0], loggerLevel.length > 1 ? loggerLevel[1] : null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                System.out.println("LOGGER_LEVEL: " + LOGGER_LEVEL);
            }
        }

        public void config() {
            if (loggerType == null) {
                System.out.println("logger type is not support");
                return;
            } else {
                System.out.println("logger type: " + loggerType.name());
            }

            for (Map.Entry<String, String> entry : LOGGER_LEVEL.entrySet()) {
                String loggerName = entry.getKey();
                String loggerLevel = entry.getValue();
                System.out.println("transform config: " + entry);

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