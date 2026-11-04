package dev.generative.equipment.runtime;
import org.junit.jupiter.api.Test;
import dev.generative.equipment.core.*;
import static org.junit.jupiter.api.Assertions.*;
class QuickJsRuntimeTest {
    @Test void cannotReportExecutionSuccessBeforeRuntimeExists() {
        var runtime = new QuickJsRuntime();
        assertThrows(UnavailableFeatureException.class, () -> runtime.prepare(null));
        var failure = assertInstanceOf(RuntimeTypes.InvocationFailure.class, runtime.invoke(null,null,null,null));
        assertEquals("NOT_IMPLEMENTED",failure.code());
        runtime.close();assertThrows(IllegalStateException.class, () -> runtime.invoke(null,null,null,null));
    }
}
