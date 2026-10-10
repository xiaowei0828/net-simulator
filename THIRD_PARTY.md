# Third-party components

Native sources are fetched using `scripts/bootstrap-native.sh`. No modifications are made to upstream sources.
The resulting APK includes license notices in `assets/licenses/`.
iOS links only the pinned lwIP fork from this dependency tree; its BSD-style LICENSE is bundled in both the app and Packet Tunnel extension.

| Component | Pinned version / commit | License |
| --- | --- | --- |
| [HevSocks5Tunnel](https://github.com/heiher/hev-socks5-tunnel) | `d9dca26c7ad0e494492244f0309e80ee583e739e` | MIT |
| [HevSocks5Core](https://github.com/heiher/hev-socks5-core) | `162dd996299fc2d2bff2dd63728f8a2cd71ed31a` | MIT |
| [HevTaskSystem](https://github.com/heiher/hev-task-system) | `328f35d903221b51811b3d02b277d665dfbdc75f` | MIT |
| [lwIP fork](https://github.com/heiher/lwip) | `e22c9d2873cd5a8aade9deb2e518b75dab05170e` | BSD-style; see bundled notice |
| [YAML fork](https://github.com/heiher/yaml) | `efa36117a8646d26d12b58e05bac472d7854a70d` | MIT; additional upstream source notices retained |
| [dnsjava](https://github.com/dnsjava/dnsjava) | 3.6.3 | BSD-3-Clause |
| [SLF4J](https://www.slf4j.org/) | 1.7.36 (API / NOP binding) | MIT |

AndroidX Test, JUnit and the Kotlin runtime are test-only dependencies. Gradle Wrapper and the Android Gradle Plugin are build tools.
