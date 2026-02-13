<p align="center">
  <img width="120" height="120" src="https://i.ibb.co/T97B4HN/Discord-Avatar.png" alt="Pano Logo">
</p>

<h1 align="center">Pano</h1>

<p align="center">
  The <b>main repository</b> for Pano, an advanced web platform powering Minecraft server websites. 🚀
</p>

<p align="center">
  <img src="https://img.shields.io/maintenance/yes/2026?style=for-the-badge" alt="Maintained">
  <a href="https://github.com/PanoMC/pano/blob/main/LICENSE"><img src="https://img.shields.io/github/license/PanoMC/pano?style=for-the-badge" alt="License"></a>
  <a href="https://panomc.com/discord"><img src="https://img.shields.io/badge/chat-on%20discord-7289da.svg?style=for-the-badge" alt="Chat"></a>
</p>

---

## 🚀 Project Status
**Current Status:** Beta - Pano is now ready for use, although small bugs may still be encountered.  
Announcements and updates are shared via our [Discord](https://panomc.com/discord).  

### 📢 Release Types
We categorize our updates into three main stages:
- **Alpha**: Early-stage development. Experimental features, potential breaking changes, and critical bugs. Use with caution.
- **Beta**: Feature-complete and generally stable. Ready for wider use, but may still have minor bugs. Recommended for testing and early adopters.
- **Release**: Stable and production-ready. Thoroughly tested for a reliable experience.

---


## 📦 Other Pano Repositories
- [**panel-ui**](https://github.com/PanoMC/panel-ui) - Pano's official management panel interface  
- [**setup-ui**](https://github.com/PanoMC/setup-ui) - Pano's setup wizard interface  
- [**vanilla-theme**](https://github.com/PanoMC/vanilla-theme) - Default official free theme for Pano  
- [**pano-mc-plugin**](https://github.com/PanoMC/pano-mc-plugin) - Minecraft in-game integration plugin (supports Spigot/Paper/Bungeecord/Velocity/Folia)  
- [**docs**](https://github.com/PanoMC/docs) - Open-source documentation for Pano  

---

## ⚡ Trying Pano
You can try Pano by downloading the latest release from [Releases](https://github.com/PanoMC/pano/releases).  

To run Pano:  

- **Double-click the JAR** inside a folder, **or**  
- Run via terminal:

```bash
java -jar Pano-<version>.jar
```

- To disable GUI:  

```bash
java -jar Pano-<version>.jar -nogui
```

For detailed guidance, visit [panomc.com/docs](https://panomc.com/docs).  

---

## 🛠️ Requirements
**For contributors / development:**  
- **JDK 11+** or **JRE 11+**  
- **MySQL 5.5+** / MariaDB  

* **Docker & Docker Compose** are optional and only required for contributors.  

---

## 🤝 Contributing
Everyone can open issues in the repository.  
- We create issues in the respective project repositories for our own development tasks.  
- Irrelevant or abusive issues may be closed or removed.  

---

### Development Guide
Clone the repository for development:

```bash
git clone --recursive https://github.com/PanoMC/pano.git
cd pano
```

Compile & run for development:

```bash
./gradlew run
```

Or use Docker:

```bash
docker-compose up
```

---

## 📄 License
Pano is licensed under **GNU GPLv3**, meaning it is fully open source. See the [LICENSE](LICENSE) file for details.
