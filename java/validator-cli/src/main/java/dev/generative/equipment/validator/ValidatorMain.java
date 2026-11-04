package dev.generative.equipment.validator;
import dev.generative.equipment.protocol.JsonContracts;
import java.nio.file.*;
/** 隔离验证的进程协议入口；S0 始终返回未执行与非零退出码，禁止误发布。 */
public final class ValidatorMain {
    public static void main(String[] args) throws Exception {
        var report = JsonContracts.MAPPER.createObjectNode();
        report.put("passed", false);
        int exit = 2;
        try {
            if (args.length != 2 || !args[0].equals("--request")) throw new IllegalArgumentException("Usage: --request <path>");
            Path path = Path.of(args[1]);
            if (!Files.isRegularFile(path) || Files.size(path) > 1048576) throw new IllegalArgumentException("Invalid request file");
            var request = JsonContracts.MAPPER.readTree(Files.readString(path));
            for (String key : new String[]{"validationId", "sourceHash", "engineBuildId", "capabilityProfileId"}) {
                if (!request.path(key).isTextual()) throw new IllegalArgumentException("Missing " + key);
                report.set(key, request.get(key));
            }
            var check = report.putArray("checks").addObject();
            check.put("name", "isolated-runtime").put("status", "NOT_RUN");
            check.putArray("diagnostics").addObject().put("code", "NOT_IMPLEMENTED").put("message", "S0 has no validated QuickJS runtime; publishing is forbidden.");
        } catch (Exception e) {
            report.put("code", "INVALID_REQUEST").put("message", "A valid validation request file is required.");
        }
        report.putNull("compiledArtifact");
        System.out.println(JsonContracts.MAPPER.writeValueAsString(report));
        System.exit(exit);
    }
}
