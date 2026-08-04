# Oculus DH3 Compat

Oculus DH3 Compat is an unofficial Oculus 1.16.5 fork that adds support for
Distant Horizons 3.x. It is intended for Minecraft 1.16.5 modpacks that use
Oculus shaders together with recent Distant Horizons 3.x builds.

## Compatibility

- Minecraft 1.16.5
- Forge 36.2.29 or newer 36.x builds
- Distant Horizons 3.2.x (`3.2.0-b` or newer, below `3.3.0`)
- Rubidium 0.2.13

The fork keeps Oculus's `oculus` mod ID so that existing compatibility checks
continue to work. It replaces Oculus and must not be installed alongside an
official Oculus jar.

## Changes from Oculus

- Integration with the rendering API used by Distant Horizons 3.x.
- Shader program and framebuffer overrides for distant terrain.
- Separate handling for opaque and transparent Distant Horizons passes.
- Distant Horizons depth texture support for shader-pack rendering.
- Compatibility guards that remain inactive when Distant Horizons is absent.

## Source and license

This project is based on the 1.16.5 branch of
[Asek3/Oculus](https://github.com/Asek3/Oculus), itself a Forge fork of
[Iris Shaders](https://github.com/IrisShaders/Iris).

The earlier [Oculus 1.16.5 DH support patch](https://github.com/PlxelBuilder/Oculus-1.16.5-DH-Support)
backported Iris's preliminary Distant Horizons 2.0 support. This fork keeps
parts of that groundwork and updates the integration for Distant Horizons 3.x.

Oculus DH3 Compat is distributed under the
[GNU Lesser General Public License v3.0](LICENSE). See [NOTICE.md](NOTICE.md)
for attribution and fork information.

*This is an unofficial community fork and is not affiliated with the Oculus or
Iris teams.*
