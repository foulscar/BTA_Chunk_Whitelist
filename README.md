# Chunk Whitelist

## Overview

Chunk Whitelist is a server-side BTA mod that restricts chunk generation, loading, interaction and visibility to a per-dimension whitelist. To players, unwhitelisted chunks appear to act as a border.

## Commands


| Command | Description |
| --- | --- |
| `/chunk whitelist` | Show command usage. |
| `/chunk whitelist <dimensionId>` | Report the whitelist setting for the specified dimension. |
| `/chunk whitelist <dimensionId> add single <x> <z>` | Add one chunk to the dimension whitelist. |
| `/chunk whitelist <dimensionId> add rect <x1> <z1> <x2> <z2>` | Add an inclusive rectangle of chunks. |
| `/chunk whitelist <dimensionId> add radius <number>` | Add a circular area centered on your current chunk. |
| `/chunk whitelist <dimensionId> remove single <x> <z>` | Remove one chunk from the dimension whitelist. |
| `/chunk whitelist <dimensionId> remove rect <x1> <z1> <x2> <z2>` | Remove an inclusive rectangle of chunks. |
| `/chunk whitelist <dimensionId> remove radius <number>` | Remove a circular area centered on your current chunk. |
| `/chunk whitelist <dimensionId> enable` | Enable the whitelist for the specified dimension. |
| `/chunk whitelist <dimensionId> disable` | Disable the whitelist for the specified dimension. |
| `/chunk whitelist <dimensionId> status` | Report the whitelist setting for the specified dimension. |
| `/chunk whitelist <dimensionId> list` | List subscribed whitelisted chunks in the specified dimension. |
| `/chunk tp <chunkX> <chunkZ>` | Teleport to a chunk. |

## Data

At server startup, a CSV whitelist file is created for every dimension inside the save folder. Dimension whitelist settings are stored in `data/chunkwhitelist/dimensions.dat`.

- Whitelists: `data/chunkwhitelist/<dimensionId>.csv`
- Dimension settings: `data/chunkwhitelist/dimensions.dat`

## Features

- Non-whitelisted chunks are sent to the player as empty/fake chunks, and all interactions are rejected/intercepted.
  - Block placing/breaking gets reverted (items don't delete from inventory). Items teleport back. Entities and projectiles bounce back.  Mobs don't pathfind outside of whitelisted chunks.
- Players in creative and spectator mode can move outside of whitelisted chunks.
- You can enable/disable your current dimension's whitelist and the client will receive chunk updates
