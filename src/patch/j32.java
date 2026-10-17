import com.aspiro.wamp.App;
import com.datadog.android.internal.attributes.ViewScopeInstrumentationType;
import com.tidal.android.core.devicetype.DeviceType;
import io.github.resurekto.tidalgtvmod.TcVolumeSync;

import java.util.LinkedHashMap;

/**
 * Replaces {@code j32}. {@link #b} installs the real broadcast manager ({@code lk4}) on TV
 * too (stock: no-op {@code nt4}). The other methods are unchanged stock code.
 */
public final class j32 {

    private j32() {
    }

    public static void a(LinkedHashMap<String, ViewScopeInstrumentationType> attributes, ViewScopeInstrumentationType type) {
        attributes.put(type.getKey().toString(), type);
    }

    /** Stock: {@code enabled = type != AUTO && !isTv(app)}. */
    public static void b(App app) {
        boolean enabled = DeviceType.a.a(app) != DeviceType.AUTO;
        i32.a = enabled ? new lk4(app) : new nt4();
        i32.b = enabled;
        if (enabled) {
            TcVolumeSync.install(app);
        }
    }

    public static void c(rg0 component) {
        d(component.t());
    }

    public static void d(isb player) {
        ydh.a(new b25(player));
    }
}
