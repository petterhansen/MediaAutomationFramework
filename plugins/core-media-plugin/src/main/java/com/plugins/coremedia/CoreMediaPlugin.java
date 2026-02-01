import com.framework.api.MediaPlugin;
import com.framework.core.Kernel;
import com.plugins.coremedia.TranscoderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class CoreMediaPlugin implements MediaPlugin {
    private static final Logger logger = LoggerFactory.getLogger(CoreMediaPlugin.class);
    private DownloadService downloader;
    private TranscoderService transcoder;

    @Override
    public String getName() { return "CoreMedia"; }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public void onEnable(Kernel kernel) {
        // Services instanziieren (liegen jetzt im Plugin-Package!)
        this.downloader = new DownloadService(kernel);
        this.transcoder = new TranscoderService();

        // 1. Download Handler registrieren
        kernel.getPipelineManager().setDownloadHandler(item -> {
            // Logik zum Extrahieren von Headern/Ordnern bleibt hier im Plugin
            Map<String, String> headers = null;
            if (item.getMetadata().containsKey("headers")) {
                headers = (Map<String, String>) item.getMetadata().get("headers");
            }

            String folderName = kernel.getConfigManager().getConfig().downloadPath;
            if (item.getMetadata().containsKey("creator")) folderName = (String) item.getMetadata().get("creator");

            return downloader.downloadFile(item.getSourceUrl(), folderName, item.getOriginalName(), headers);
        });

        // 2. Processing Handler registrieren
        kernel.getPipelineManager().setProcessingHandler(item -> {
            boolean reencode = item.getParentTask().getInt("reencode", 0) == 1;
            return transcoder.processMedia(item.getDownloadedFile(), reencode);
        });

        kernel.registerService(TranscoderService.class, this.transcoder);

        logger.info("💿 CoreMedia Services (DL/FFmpeg) bereit.");
    }

    @Override
    public void onDisable() {
        // Optional: Laufende Downloads abbrechen
    }
}