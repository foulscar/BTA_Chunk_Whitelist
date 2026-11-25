# Chunk Whitelist

Server-side BTA mod that restricts chunk generation, loading, interaction and visibility to a per-dimension whitelist. The mod makes it appear to the client as if unwhitelisted chunks are a border.

At server startup, a CSV whitelist file is created for every loaded dimension in `data/chunkwhitelist/<dimensionId>.csv` inside the save folder. Non-whitelisted chunks are sent to the player as empty/fake chunks and all interactions are rejected/intercepted. 

Block placing/breaking gets reverted (items don't delete from inventory). Items teleport back. Entities and projectiles bounce back. Mobs don't pathfind outside of whitelisted chunks. Players in creative and spectator mode can move outside of whitelisted chunks.

Run `/chunk whitelist` for command usage. Operators can use `/chunk whitelist add` or `/chunk whitelist remove` in-game to change the chunk they are standing in, or provide `<chunkX> <chunkZ>` to specify coordinates. I also added `/chunk tp` and `/chunk whitelist list` as helper commands.
