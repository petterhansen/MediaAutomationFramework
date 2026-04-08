# Media Automation Framework

A powerful, plugin-based Java framework for automated media processing, downloading, and distribution. Built with a modular architecture supporting Telegram integration, web dashboards, and highly extensible media sources.

## 🌟 Features

- **🔌 Plugin Architecture**: Modular design with hot-swappable plugins for specific media sources and features.
- **📥 Multi-Source Downloads**: Support for various media sources (Booru, Party sites, YouTube, TikTok, Socials, Gallery-dl).
- **🎬 Media Processing**: Automated transcoding, dynamic watermarking (including preview videos), and optimization using FFmpeg.
- **📱 Telegram Integration**: Comprehensive bot commands, automated batch uploads, and fast media group support.
- **🌐 Web Dashboard**: Real-time monitoring, queue management, media player, and gallery browser.
- **📚 Manga Library**: Dedicated Manga plugin for reading, organizing, and downloading manga with user profiles.
- **⚙️ Pipeline System**: Robust three-stage processing (Download → Process → Upload) handling large queues safely.
- **🛡️ Media Safety & Moderation**: Built-in limits, regex content blocking, and safety constraints.
- **🔐 Security**: Role-based authentication, user-specific path isolation, and credential handling.

---

## 📋 Prerequisites

### Required
- **Java 21+** (Built for modern patterns)
- **Maven 3.6+** for building from source
- **FFmpeg & FFprobe** for media processing 

### Optional
- **yt-dlp** (For YouTube and generic extractions) 
- **gallery-dl** (For massive gallery scraping)
- **Node/Python plugins** depending on specific scrapers used

---

## 🚀 Quick Start

### 1. Clone the Repository

```bash
git clone <your-repository-url>
cd MediaAutomationFramework
```

### 2. Configure the Application

#### Create Telegram Configuration

```bash
cp telegram_config.json.example telegram_config.json
```

Edit `telegram_config.json`:
```json
{
  "token": "YOUR_BOT_TOKEN_HERE",
  "chatId": "YOUR_CHAT_ID_HERE",
  "interval": 60,
  "publicUrl": "http://localhost:26959/status"
}
```

> [!IMPORTANT]
> **Never commit `telegram_config.json` to version control!** It is already excluded in `.gitignore`.

#### Configure Main Settings

The application will create `tools/config.json` on first run. Customize it to enable or disable any of the 13 built-in plugins:

```json
{
  "debugMode": false,
  "downloadPath": "downloads",
  "telegramToken": "YOUR_TOKEN",
  "telegramAdminId": "YOUR_CHAT_ID",
  "plugins": {
    "CoreCommands": true,
    "CoreMedia": true,
    "TelegramIntegration": true,
    "WebDashboard": true,
    "YouTubePlugin": true,
    "TikTokPlugin": true,
    "MangaPlugin": true
  },
  "pluginConfigs": {
    "CoreMedia": {
      "ffmpeg_path": "tools/ffmpeg.exe",
      "watermark_enabled": "true",
      "watermark_text": "Media Automation Framework",
      "split_threshold_mb": "1999"
    }
  }
}
```

### 3. Build the Project

```bash
mvn clean package
```

This creates a shaded JAR with all dependencies bundled inside.

### 4. Run the Application

```bash
java -jar core/target/core-1.0.0.jar
```

The application will:
- Start the Web Dashboard on `http://localhost:6875`
- Initialize the Telegram listener (if active)
- Validate dependencies and initialize `media_cache/`

---

## 🔐 Security Best Practices

### Credential Management

> [!CAUTION]
> **Critical Security Rules**

1. **Never commit credentials to version control**
2. **Dashboard Security**: The web interface uses HTTP Basic Authentication matched dynamically against session tokens generated at startup. Admins must authorize correctly matching the `AuthManager`.
3. **Path Traversal Protection**: The framework strictly validates all file paths across downloads, cache, and custom uploads.

---

## 🔌 Plugin System

### Available Plugins

1. **CoreCommands** - System commands (`/status`, `/help`, `/queue`, etc.)
2. **CoreMedia** - Media processing, automated transcoding, and watermarking
3. **TelegramIntegration** - Telegram bot and massive chunked uploads
4. **DashboardPlugin** - Web interface, live status, and viewers
5. **BooruPlugin** - Booru image board integration
6. **PartyPlugin** - Coomer/Kemono integration
7. **YouTubePlugin** - High quality YouTube extraction
8. **TikTokPlugin** - TikTok video and slideshow integration
9. **SocialsPlugin** - Generic social media platform integration
10. **MangaPlugin** - Manga reading, library management, and secured profiles
11. **MediaSafetyPlugin** - Regex moderation and safety limits for content
12. **GalleryDlPlugin** - Native Gallery-dl extraction support
13. **DownloadCmdPlugin** - CLI-style fast downloader commands

---

## 💻 Usage Examples

### Web Dashboard

Access at `http://localhost:6875`. (Check logs for dynamic admin password on first run).
- Real-time pipeline monitoring
- Remote command execution
- Media viewers (Videos, Galleries, Manga)

### Telegram Commands

| Command | Description |
|---------|-------------|
| `/help` / `/start` | Show available commands |
| `/status` | System status and memory |
| `/queue` or `/q` | View current processing pipeline |
| `/log` | Show recent log entries |
| `/clean` | Clean empty directories |
| `/yt <url>` | Download from YouTube directly |
| `/tt <url>` | Download TikTok (supports slideshows) |
| `/dl <url>` | Fast standard download |
| `/gdl <url>` | Gallery-dl batch extraction |
| `/cm <query>` | Search Coomer |
| `/km <query>` | Search Kemono |
| `/r34 <tags>` | Search Rule34 Booru |
| `/xb`, `/sb` | Search alternative Boorus |
| `/local <folder>` | Process a local server resource |
| `manga_invite` | Generate Manga profile signup links |
| `/stop` | Gracefully shutdown system |

---

## 🛠️ Troubleshooting

### Common Issues

#### FFmpeg Not Found
**Error**: `Cannot run program "ffmpeg"`
**Solution**: Verify FFmpeg is installed and explicitly set `ffmpeg_path` in tools/config.json.

#### Videos Grayed Out on Upload
**Solution**: Ensure you are on the latest build. MAF updated its multi-part API syntax to accommodate recent Telegram API changes, ensuring auto-generated thumbnails and long-videos play reliably. 

#### Web Dashboard Not Reachable
**Error**: `Address already in use: 6875`
**Solution**: Change port in Dashboard configuration or terminate existing processes running on port 6875.

---

## 👨‍💻 Development

### Project Structure

```
MediaAutomationFramework/
├── core/                          # Core framework kernel, interfaces, pipeline
├── plugins/
│   ├── core-media-plugin/         # Core FFmpeg processing logic
│   ├── telegram-plugin/           # Main TG bot listener and uploader
│   ├── dashboard-plugin/          # Dashboard HTTP server + Frontend
│   ├── booru-plugin/              # Booru sources
│   ├── party-plugin/              # Party sources
│   ├── youtube-plugin/            # YT-Dlp Wrappers
│   ├── tiktok-plugin/             # TikTok API + Slideshow logic
│   ├── manga-plugin/              # Full Manga library ecosystem
│   └── ... 
├── tools/                         # Expected runtime bins (FFmpeg, configs)
├── media_cache/                   # Transcoding output and pipeline cache
└── pom.xml                        # Maven Parent POM
```

### Creating Custom Plugins

1. Implement `MediaPlugin` interface.
2. Register Services/Pipelines in `onEnable(Kernel kernel)`.
3. Add Module reference to the Parent POM.

---

**Built with ❤️ using Java 21 and Maven**
