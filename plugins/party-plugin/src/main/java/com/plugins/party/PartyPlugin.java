import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
// PartySource liegt jetzt HIER im Package, nicht mehr im Framework
import com.plugins.party.internal.PartySource;

public class PartyPlugin implements MediaPlugin {
    @Override
    public String getName() { return "PartySource"; }
    @Override
    public String getVersion() { return "1.0"; }

    @Override
    public void onEnable(Kernel kernel) {
        // Wir registrieren die Source beim QueueManager
        kernel.getQueueManager().registerExecutor("SEARCH_BATCH", new PartySource(kernel));
    }

    @Override
    public void onDisable() {}
}