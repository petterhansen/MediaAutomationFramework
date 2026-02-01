import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.plugins.booru.internal.BooruSource;

public class BooruPlugin implements MediaPlugin {
    @Override
    public String getName() { return "BooruSource"; }
    @Override
    public String getVersion() { return "1.0"; }

    @Override
    public void onEnable(Kernel kernel) {
        kernel.getQueueManager().registerExecutor("BOORU_BATCH", new BooruSource(kernel));
    }

    @Override
    public void onDisable() {}
}