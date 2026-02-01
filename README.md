# Media Automation Framework

A powerful, plugin-based Java framework for automated media processing, downloading, and distribution. Built with a modular architecture supporting Telegram integration, web dashboard, and extensible media sources.

## 🌟 Features

- **🔌 Plugin Architecture**: Modular design with hot-swappable plugins
- **📥 Multi-Source Downloads**: Support for various media sources (Booru, Party sites, browser extraction)
- **🎬 Media Processing**: Automated transcoding, watermarking, and optimization using FFmpeg
- **📱 Telegram Integration**: Bot commands, automated uploads, and media group support
- **🌐 Web Dashboard**: Real-time monitoring, queue management, and file browser
- **⚙️ Pipeline System**: Three-stage processing (Download → Process → Upload)
- **📊 Statistics & History**: Track downloads, processing times, and creator statistics
- **🔐 Security**: Role-based authentication, path validation, and credential management

---

## 📋 Prerequisites

### Required
- **Java 25** or higher ([Download](https://www.oracle.com/java/technologies/downloads/))
- **Maven 3.6+** for building ([Download](https://maven.apache.org/download.cgi))
- **FFmpeg & FFprobe** for media processing ([Download](https://ffmpeg.org/download.html))

### Optional
- **aria2c** for faster downloads ([Download](https://aria2.github.io/))

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
  "lastScheduled": 0,
  "publicUrl": "http://localhost:26959/status"
}
```

> [!IMPORTANT]
> **Never commit `telegram_config.json` to version control!** It's already in `.gitignore`.

#### Configure Main Settings

The application will create `tools/config.json` on first run with default values. You can customize:

```json
{
  "debugMode": false,
  "downloadPath": "downloads",
  "tempDir": "temp",
  "telegramEnabled": true,
  "telegramToken": "YOUR_TOKEN",
  "telegramAdminId": "YOUR_CHAT_ID",
  "plugins": {
    "CoreCommands": true,
    "CoreMedia": true,
    "TelegramIntegration": true,
    "WebDashboard": true
  },
  "pluginConfigs": {
    "CoreMedia": {
      "ffmpeg_path": "tools/ffmpeg.exe",
      "ffprobe_path": "tools/ffprobe.exe",
      "watermark_enabled": "false",
      "watermark_text": "Your Text Here",
      "watermark_size": "24",
      "watermark_opacity": "0.5",
      "split_threshold_mb": "1999"
    }
  }
}
```

### 3. Install FFmpeg

#### Windows
1. Download FFmpeg from [ffmpeg.org](https://ffmpeg.org/download.html)
2. Extract to `tools/` directory
3. Ensure `tools/ffmpeg.exe` and `tools/ffprobe.exe` exist

#### Linux/Mac
```bash
# Ubuntu/Debian
sudo apt-get install ffmpeg

# macOS
brew install ffmpeg
```

Update config to point to system FFmpeg:
```json
"ffmpeg_path": "/usr/bin/ffmpeg",
"ffprobe_path": "/usr/bin/ffprobe"
```

### 4. Build the Project

```bash
mvn clean package
```

This creates `core/target/core-1.0.0.jar` (shaded JAR with all dependencies).

### 5. Run the Application

```bash
java -jar core/target/core-1.0.0.jar
```

The application will:
- Start the web dashboard on `http://localhost:6875`
- Initialize the Telegram bot (if configured)
- Create necessary directories (`logs/`, `media_cache/`, etc.)

---

## 🔐 Security Best Practices

### Credential Management

> [!CAUTION]
> **Critical Security Rules**

1. **Never commit credentials to version control**
   - `telegram_config.json` ✅ Already in `.gitignore`
   - `tools/config.json` ✅ Already in `.gitignore`

2. **Rotate API tokens regularly**
   - Use BotFather to regenerate Telegram tokens
   - Update configuration files after rotation

3. **Restrict file permissions** (Linux/Mac)
   ```bash
   chmod 600 telegram_config.json
   chmod 600 tools/config.json
   ```

4. **Use environment variables** (Production)
   ```bash
   export TELEGRAM_TOKEN="your_token_here"
   export TELEGRAM_CHAT_ID="your_chat_id"
   ```

### Web Dashboard Security

The dashboard uses HTTP Basic Authentication. Configure credentials in `tools/auth.json`:

```json
{
  "webUser": "admin",
  "webPassword": "your_secure_password_here"
}
```

> [!WARNING]
> Change default credentials before deploying to production!

### Path Traversal Protection

The framework validates all file paths to prevent directory traversal attacks. All file operations are restricted to:
- `media_cache/`
- `downloads/`
- `temp/`

---

## 📚 Configuration Guide

### Plugin Configuration

Each plugin can have custom settings in `pluginConfigs`:

#### CoreMedia Plugin

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `ffmpeg_path` | String | `tools/ffmpeg.exe` | Path to FFmpeg executable |
| `ffprobe_path` | String | `tools/ffprobe.exe` | Path to FFprobe executable |
| `watermark_enabled` | Boolean | `false` | Enable watermarking |
| `watermark_text` | String | `Media Automation Framework` | Watermark text |
| `watermark_size` | Integer | `24` | Font size for watermark |
| `watermark_opacity` | Float | `0.5` | Watermark opacity (0.0-1.0) |
| `split_threshold_mb` | Integer | `1999` | Split videos larger than this (MB) |
| `use_gpu` | Boolean | `false` | Use GPU acceleration |
| `preset` | String | `fast` | FFmpeg preset (ultrafast, fast, medium, slow) |
| `crf` | Integer | `23` | Constant Rate Factor (0-51, lower = better quality) |

#### Telegram Plugin

Configured via `telegramToken` and `telegramAdminId` in main config.

---

## 🔌 Plugin System

### Available Plugins

1. **CoreCommands** - System commands (`/status`, `/help`, `/queue`, etc.)
2. **CoreMedia** - Media processing and transcoding
3. **TelegramIntegration** - Telegram bot and uploads
4. **DashboardPlugin** - Web interface
5. **BooruPlugin** - Booru image board integration
6. **PartyPlugin** - Coomer/Kemono integration

### Enabling/Disabling Plugins

Edit `tools/config.json`:

```json
"plugins": {
  "CoreCommands": true,
  "CoreMedia": true,
  "TelegramIntegration": false,  // Disable Telegram
  "WebDashboard": true
}
```

Restart the application for changes to take effect.

---

## 💻 Usage Examples

### Web Dashboard

Access at `http://localhost:6875` (default credentials: admin/admin)

**Features**:
- Real-time system monitoring
- Queue management
- File browser and media manager
- Command execution
- Statistics and analytics

### Telegram Commands

| Command | Description |
|---------|-------------|
| `/help` | Show available commands |
| `/status` | System status and uptime |
| `/queue` | View current queue |
| `/log` | Show recent log entries |
| `/clean` | Clean empty folders |
| `/cm <query>` | Search Coomer |
| `/km <query>` | Search Kemono |
| `/r34 <tags>` | Search Rule34 |
| `/stop` | Shutdown system |

### API Usage

#### Push URLs for Download

```bash
curl -X POST http://localhost:6875/push \
  -H "Content-Type: application/json" \
  -u admin:admin \
  -d '{
    "urls": ["https://example.com/video.mp4"],
    "folder": "MyFolder"
  }'
```

#### Execute Commands

```bash
curl -X POST http://localhost:6875/api/command \
  -H "Content-Type: application/json" \
  -u admin:admin \
  -d '{"cmd": "/status"}'
```

---

## 🛠️ Troubleshooting

### Common Issues

#### FFmpeg Not Found

**Error**: `Cannot run program "ffmpeg"`

**Solution**:
1. Verify FFmpeg is installed: `ffmpeg -version`
2. Update `ffmpeg_path` in config
3. On Windows, ensure `tools/ffmpeg.exe` exists

#### Telegram Bot Not Responding

**Checklist**:
- ✅ Verify token is correct in `telegram_config.json`
- ✅ Check `telegramEnabled: true` in config
- ✅ Ensure bot has permission to send messages
- ✅ Check logs for connection errors

#### Port Already in Use

**Error**: `Address already in use: 6875`

**Solution**: Change port in `DashboardServer.java` or kill existing process:
```bash
# Windows
netstat -ano | findstr :6875
taskkill /PID <PID> /F

# Linux/Mac
lsof -i :6875
kill -9 <PID>
```

### Log Locations

- **Latest log**: `logs/latest.log`
- **Session logs**: `logs/session-YYYY-MM-DD_HH-mm-ss.log`
- **Debug mode**: Set `debugMode: true` in config

---

## 👨‍💻 Development

### Building from Source

```bash
# Clean build
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Build specific module
mvn clean package -pl core
```

### Project Structure

```
MediaAutomationFramework/
├── core/                          # Core framework
│   └── src/main/java/com/framework/
│       ├── Main.java             # Application entry point
│       ├── core/                 # Kernel, config, pipeline
│       ├── api/                  # Plugin interfaces
│       ├── common/               # Utilities, auth
│       └── services/             # Statistics, history
├── plugins/
│   ├── core-media-plugin/        # Media processing
│   ├── telegram-plugin/          # Telegram integration
│   ├── dashboard-plugin/         # Web dashboard
│   ├── booru-plugin/             # Booru sources
│   └── party-plugin/             # Party sources
├── web/                          # Dashboard HTML/CSS/JS
├── tools/                        # FFmpeg, config files
└── pom.xml                       # Maven parent POM
```

### Creating a Plugin

1. Implement `MediaPlugin` interface:

```java
public class MyPlugin implements MediaPlugin {
    @Override
    public String getName() { return "MyPlugin"; }
    
    @Override
    public String getVersion() { return "1.0.0"; }
    
    @Override
    public void onEnable(Kernel kernel) {
        // Initialize plugin
    }
    
    @Override
    public void onDisable() {
        // Cleanup
    }
}
```

2. Add to parent `pom.xml`:
```xml
<module>plugins/my-plugin</module>
```

3. Enable in config:
```json
"plugins": {
  "MyPlugin": true
}
```

---

## 📝 Logging Framework

### Current Approach

The framework uses a custom logging solution that redirects `System.out` and `System.err` to both console and log files. This captures all output including third-party libraries.

**Pros**:
- Simple and effective
- Captures everything
- Creates session and latest logs

**Cons**:
- No log levels or filtering
- No automatic rotation

### Future Improvement: Logback

For production deployments, consider upgrading to Logback:

1. Replace `slf4j-simple` with `logback-classic` in `core/pom.xml`:
```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.4.14</version>
</dependency>
```

2. Create `src/main/resources/logback.xml`:
```xml
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

3. Remove custom `MultiOutputStream` from `Main.java`

---

## 📄 License

[Specify your license here]

---

## 🙏 Credits

- **FFmpeg** - Media processing
- **SLF4J** - Logging facade
- **Gson** - JSON processing
- **Apache Commons FileUpload** - File uploads

---

## 📞 Support

For issues and questions:
- Check [Troubleshooting](#troubleshooting) section
- Review logs in `logs/latest.log`
- Enable debug mode for verbose output

---

**Built with ❤️ using Java 25 and Maven**
