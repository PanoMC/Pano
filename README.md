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
  <a href="https://discord.gg/6vVy72wgXT"><img src="https://img.shields.io/badge/chat-on%20discord-7289da.svg?style=for-the-badge" alt="Chat"></a>
</p>

---

## 🚀 Project Status
**Current Status:** Alpha - Pano is actively developed and early in its lifecycle.  
Statuses: **Alpha**, **Beta**, **Release**.  
Announcements and updates are shared via our [Discord](https://discord.gg/6vVy72wgXT).  

* As an Alpha project, Pano **may contain breaking changes** and its final release could differ significantly.  

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

For detailed guidance, visit [dev.panomc.com/docs](https://dev.panomc.com/docs).  

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
