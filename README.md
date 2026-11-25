# Chunk Whitelist

## Overview

Chunk Whitelist is a server-side BTA mod that restricts chunk generation, loading, interaction and visibility to a per-dimension whitelist. To players, unwhitelisted chunks appear to act as a border.

## Commands

Run these commands in-game from the `/chunk` root:

| Command | Description |
| --- | --- |
| `/chunk whitelist` | Show command usage. |
| `/chunk whitelist add [chunkX] [chunkZ]` | Add the chunk you are standing in, or the specified chunk, to the whitelist. |
| `/chunk whitelist remove [chunkX] [chunkZ]` | Remove the chunk you are standing in, or the specified chunk, from the whitelist. |
| `/chunk whitelist enable <dimensionId>` | Enable the whitelist for the specified dimension. |
| `/chunk whitelist disable <dimensionId>` | Disable the whitelist for the specified dimension. |
| `/chunk whitelist status <dimensionId>` | Report the whitelist setting for the specified dimension. |
| `/chunk whitelist list` | List subscribed whitelisted chunks. |
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
