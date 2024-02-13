<p align="center"><img width="100" src="https://i.ibb.co/wy0LVmD/Pano-Icon.png" alt="Pano logo"></p>
<p align="center">
  Pano is an advanced web platform that comfort your Minecraft server's website needs. 
</p>
<p align="center">
  <img src="https://img.shields.io/maintenance/yes/2024?style=for-the-badge" alt="Maintained">
  <a href="https://github.com/panocms/pano/blob/dev/LICENSE"><img src="https://img.shields.io/github/license/panocms/pano?style=for-the-badge" alt="License"></a>
  <a href="https://discord.gg/Qm8bfzJ9HB"><img src="https://img.shields.io/badge/chat-on%20discord-7289da.svg?style=for-the-badge" alt="Chat"></a>
</p>

---

#### Project Status
Pano is still under heavy development. There can be breaking changes but we're trying to keep them as minimum as possible.

#### Prerequisites
JDK 8+ <br>
MySQL v5+ / MariaDB <br>
Docker & Docker Compose (optional)

## Development
#### Getting Started
Clone this repository.

```bash
git clone --recursive https://gitlab.com/defio-workshop/pano/pano-web-platform.git
cd pano-web-platform
```

##### Compile & Run & Debug

```bash
./gradlew run
```


### Using Docker (recommended)

```bash
docker-compose up
```

##### MySQL Default Values

```bash
database: pano
root password: pano
```

## Contributing
Merge requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## License
This project is licensed under the GNU GPLv3 License - see the [LICENSE](LICENSE) file for details
