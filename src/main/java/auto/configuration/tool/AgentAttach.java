import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/**
 * @author zengzhifei
 * 2022/6/10 16:37
 */
public class AgentAttach {
    public static void main(String[] args)
            throws IOException, AttachNotSupportedException, AgentLoadException, AgentInitializationException {
        String agent = System.getProperty("user.dir") + "/target/ac-jar-with-dependencies.jar";
        System.out.println("AgentAttach load args : " + String.join("\n", args));
        System.out.println("AgentAttach load start: " + agent);
        List<VirtualMachineDescriptor> list = VirtualMachine.list();
        for (VirtualMachineDescriptor vm : list) {
            if (vm.displayName().endsWith(".jar") || vm.displayName().endsWith("Application")) {
                VirtualMachine virtualMachine = VirtualMachine.attach(vm.id());
                try {
                    System.out.println("VirtualMachine: " + vm.displayName() + " load agent...");
                    virtualMachine.loadAgent(agent, String.join("\n", args));
                } finally {
                    virtualMachine.detach();
                }
            }
        }
        System.out.println("AgentAttach load done : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
    }
}
