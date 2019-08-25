/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.storage.implementation.mongodb;

import com.google.common.base.Strings;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;

import me.lucko.luckperms.api.actionlog.Action;
import me.lucko.luckperms.api.context.Context;
import me.lucko.luckperms.api.context.ContextSet;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.api.model.DataType;
import me.lucko.luckperms.api.model.PlayerSaveResult;
import me.lucko.luckperms.api.node.HeldNode;
import me.lucko.luckperms.api.node.Node;
import me.lucko.luckperms.common.actionlog.Log;
import me.lucko.luckperms.common.actionlog.LoggedAction;
import me.lucko.luckperms.common.bulkupdate.BulkUpdate;
import me.lucko.luckperms.common.bulkupdate.comparison.Constraint;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.Track;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.UserIdentifier;
import me.lucko.luckperms.common.model.manager.group.GroupManager;
import me.lucko.luckperms.common.model.manager.track.TrackManager;
import me.lucko.luckperms.common.node.factory.NodeFactory;
import me.lucko.luckperms.common.node.model.HeldNodeImpl;
import me.lucko.luckperms.common.node.model.NodeDataContainer;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.storage.implementation.StorageImplementation;
import me.lucko.luckperms.common.storage.misc.PlayerSaveResultImpl;
import me.lucko.luckperms.common.storage.misc.StorageCredentials;

import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class MongoStorage implements StorageImplementation {
    private final LuckPermsPlugin plugin;

    private final StorageCredentials configuration;
    private MongoClient mongoClient;
    private MongoDatabase database;
    private final String prefix;
    private final String connectionUri;

    public MongoStorage(LuckPermsPlugin plugin, StorageCredentials configuration, String prefix, String connectionUri) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.prefix = prefix;
        this.connectionUri = connectionUri;
    }

    @Override
    public LuckPermsPlugin getPlugin() {
        return this.plugin;
    }

    @Override
    public String getImplementationName() {
        return "MongoDB";
    }

    @Override
    public void init() {
        if (!Strings.isNullOrEmpty(this.connectionUri)) {
            this.mongoClient = new MongoClient(new MongoClientURI(this.connectionUri));
        } else {
            MongoCredential credential = null;
            if (!Strings.isNullOrEmpty(this.configuration.getUsername())) {
                credential = MongoCredential.createCredential(
                        this.configuration.getUsername(),
                        this.configuration.getDatabase(),
                        Strings.isNullOrEmpty(this.configuration.getPassword()) ? null : this.configuration.getPassword().toCharArray()
                );
            }

            String[] addressSplit = this.configuration.getAddress().split(":");
            String host = addressSplit[0];
            int port = addressSplit.length > 1 ? Integer.parseInt(addressSplit[1]) : 27017;
            ServerAddress address = new ServerAddress(host, port);

            if (credential == null) {
                this.mongoClient = new MongoClient(address);
            } else {
                this.mongoClient = new MongoClient(address, credential, MongoClientOptions.builder().build());
            }
        }
        
        this.database = this.mongoClient.getDatabase(this.configuration.getDatabase());
    }

    @Override
    public void shutdown() {
        if (this.mongoClient != null) {
            this.mongoClient.close();
        }
    }

    @Override
    public Map<String, String> getMeta() {
        Map<String, String> ret = new LinkedHashMap<>();
        boolean success = true;

        long start = System.currentTimeMillis();
        try {
            this.database.runCommand(new Document("ping", 1));
        } catch (Exception e) {
            success = false;
        }
        long duration = System.currentTimeMillis() - start;

        if (success) {
            ret.put("Ping", "&a" + duration + "ms");
            ret.put("Connected", "true");
        } else {
            ret.put("Connected", "false");
        }

        return ret;
    }

    @Override
    public void logAction(Action entry) {
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "action");

        Document doc = new Document()
                .append("timestamp", entry.getTimestamp().getEpochSecond())
                .append("source", new Document()
                        .append("uniqueId", entry.getSource().getUniqueId())
                        .append("name", entry.getSource().getName())
                );

        Document target = new Document()
                .append("type", entry.getTarget().getType().name())
                .append("name", entry.getTarget().getName());

        if (entry.getTarget().getUniqueId().isPresent()) {
            target.append("uniqueId", entry.getTarget().getUniqueId().get());
        }

        doc.append("target", target);
        doc.append("description", entry.getDescription());

        c.insertOne(doc);
    }

    @Override
    public Log getLog() {
        Log.Builder log = Log.builder();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "action");
        try (MongoCursor<Document> cursor = c.find().iterator()) {
            while (cursor.hasNext()) {
                Document d = cursor.next();

                if (d.containsKey("source")) {
                    // new format
                    Document source = d.get("source", Document.class);
                    Document target = d.get("target", Document.class);

                    UUID targetUniqueId = null;
                    if (target.containsKey("uniqueId")) {
                        targetUniqueId = target.get("uniqueId", UUID.class);
                    }

                    LoggedAction e = LoggedAction.build()
                            .timestamp(Instant.ofEpochSecond(d.getLong("timestamp")))
                            .source(source.get("uniqueId", UUID.class))
                            .sourceName(source.getString("name"))
                            .targetType(LoggedAction.parseType(target.getString("type")))
                            .target(targetUniqueId)
                            .targetName(target.getString("name"))
                            .description(d.getString("description"))
                            .build();

                    log.add(e);
                } else {
                    // old format
                    UUID actedUuid = null;
                    if (d.containsKey("acted")) {
                        actedUuid = d.get("acted", UUID.class);
                    }

                    LoggedAction e = LoggedAction.build()
                            .timestamp(Instant.ofEpochSecond(d.getLong("timestamp")))
                            .source(d.get("actor", UUID.class))
                            .sourceName(d.getString("actorName"))
                            .targetType(LoggedAction.parseTypeCharacter(d.getString("type").charAt(0)))
                            .target(actedUuid)
                            .targetName(d.getString("actedName"))
                            .description(d.getString("action"))
                            .build();

                    log.add(e);
                }
            }
        }
        return log.build();
    }

    @Override
    public void applyBulkUpdate(BulkUpdate bulkUpdate) {
        if (bulkUpdate.getDataType().isIncludingUsers()) {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "users");
            try (MongoCursor<Document> cursor = c.find().iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();

                    UUID uuid = d.get("_id", UUID.class);
                    Set<NodeDataContainer> nodes = new HashSet<>(nodesFromDoc(d));
                    Set<NodeDataContainer> results = nodes.stream()
                            .map(bulkUpdate::apply)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                    if (!nodes.equals(results)) {
                        List<Document> newNodes = results.stream()
                                .map(MongoStorage::nodeToDoc)
                                .collect(Collectors.toList());

                        d.append("permissions", newNodes).remove("perms");
                        c.replaceOne(new Document("_id", uuid), d);
                    }
                }
            }
        }

        if (bulkUpdate.getDataType().isIncludingGroups()) {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
            try (MongoCursor<Document> cursor = c.find().iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();

                    String holder = d.getString("_id");
                    Set<NodeDataContainer> nodes = new HashSet<>(nodesFromDoc(d));
                    Set<NodeDataContainer> results = nodes.stream()
                            .map(bulkUpdate::apply)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                    if (!nodes.equals(results)) {
                        List<Document> newNodes = results.stream()
                                .map(MongoStorage::nodeToDoc)
                                .collect(Collectors.toList());

                        d.append("permissions", newNodes).remove("perms");
                        c.replaceOne(new Document("_id", holder), d);
                    }
                }
            }
        }
    }

    @Override
    public User loadUser(UUID uuid, String username) {
        User user = this.plugin.getUserManager().getOrMake(UserIdentifier.of(uuid, username));
        user.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "users");
            try (MongoCursor<Document> cursor = c.find(new Document("_id", user.getUuid())).iterator()) {
                if (cursor.hasNext()) {
                    // User exists, let's load.
                    Document d = cursor.next();

                    String name = d.getString("name");
                    user.getPrimaryGroup().setStoredValue(d.getString("primaryGroup"));

                    Set<Node> nodes = nodesFromDoc(d).stream().map(NodeDataContainer::toNode).collect(Collectors.toSet());
                    user.setNodes(DataType.NORMAL, nodes);
                    user.setName(name, true);

                    boolean save = this.plugin.getUserManager().giveDefaultIfNeeded(user, false);
                    if (user.getName().isPresent() && (name == null || !user.getName().get().equalsIgnoreCase(name))) {
                        save = true;
                    }

                    if (save | user.auditTemporaryPermissions()) {
                        c.replaceOne(new Document("_id", user.getUuid()), userToDoc(user));
                    }
                } else {
                    if (this.plugin.getUserManager().shouldSave(user)) {
                        user.clearEnduringNodes();
                        user.getPrimaryGroup().setStoredValue(null);
                        this.plugin.getUserManager().giveDefaultIfNeeded(user, false);
                    }
                }
            }
        } finally {
            user.getIoLock().unlock();
        }
        return user;
    }

    @Override
    public void saveUser(User user) {
        user.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "users");
            if (!this.plugin.getUserManager().shouldSave(user)) {
                c.deleteOne(new Document("_id", user.getUuid()));
            } else {
                c.replaceOne(new Document("_id", user.getUuid()), userToDoc(user), new ReplaceOptions().upsert(true));
            }
        } finally {
            user.getIoLock().unlock();
        }
    }

    @Override
    public Set<UUID> getUniqueUsers() {
        Set<UUID> uuids = new HashSet<>();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "users");
        try (MongoCursor<Document> cursor = c.find().iterator()) {
            while (cursor.hasNext()) {
                Document d = cursor.next();
                uuids.add(d.get("_id", UUID.class));
            }
        }
        return uuids;
    }

    @Override
    public List<HeldNode<UUID>> getUsersWithPermission(Constraint constraint) {
        List<HeldNode<UUID>> held = new ArrayList<>();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "users");
        try (MongoCursor<Document> cursor = c.find().iterator()) {
            while (cursor.hasNext()) {
                Document d = cursor.next();
                UUID holder = d.get("_id", UUID.class);

                Set<NodeDataContainer> nodes = new HashSet<>(nodesFromDoc(d));
                for (NodeDataContainer e : nodes) {
                    if (!constraint.eval(e.getPermission())) {
                        continue;
                    }
                    held.add(HeldNodeImpl.of(holder, e.toNode()));
                }
            }
        }
        return held;
    }

    @Override
    public Group createAndLoadGroup(String name) {
        Group group = this.plugin.getGroupManager().getOrMake(name);
        group.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
            try (MongoCursor<Document> cursor = c.find(new Document("_id", group.getName())).iterator()) {
                if (cursor.hasNext()) {
                    Document d = cursor.next();
                    Set<Node> nodes = nodesFromDoc(d).stream().map(NodeDataContainer::toNode).collect(Collectors.toSet());
                    group.setNodes(DataType.NORMAL, nodes);
                } else {
                    c.insertOne(groupToDoc(group));
                }
            }
        } finally {
            group.getIoLock().unlock();
        }
        return group;
    }

    @Override
    public Optional<Group> loadGroup(String name) {
        Group group = this.plugin.getGroupManager().getIfLoaded(name);
        if (group != null) {
            group.getIoLock().lock();
        }
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
            try (MongoCursor<Document> cursor = c.find(new Document("_id", name)).iterator()) {
                if (!cursor.hasNext()) {
                    return Optional.empty();
                }

                if (group == null) {
                    group = this.plugin.getGroupManager().getOrMake(name);
                    group.getIoLock().lock();
                }

                Document d = cursor.next();
                Set<Node> nodes = nodesFromDoc(d).stream().map(NodeDataContainer::toNode).collect(Collectors.toSet());
                group.setNodes(DataType.NORMAL, nodes);
            }
        } finally {
            if (group != null) {
                group.getIoLock().unlock();
            }
        }
        return Optional.of(group);
    }

    @Override
    public void loadAllGroups() {
        List<String> groups = new ArrayList<>();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
        try (MongoCursor<Document> cursor = c.find().iterator()) {
            while (cursor.hasNext()) {
                String name = cursor.next().getString("_id");
                groups.add(name);
            }
        }

        boolean success = true;
        for (String g : groups) {
            try {
                loadGroup(g);
            } catch (Exception e) {
                e.printStackTrace();
                success = false;
            }
        }

        if (!success) {
            throw new RuntimeException("Exception occurred whilst loading a group");
        }

        GroupManager<?> gm = this.plugin.getGroupManager();
        gm.getAll().values().stream()
                .filter(g -> !groups.contains(g.getName()))
                .forEach(gm::unload);
    }

    @Override
    public void saveGroup(Group group) {
        group.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
            c.replaceOne(new Document("_id", group.getName()), groupToDoc(group), new ReplaceOptions().upsert(true));
        } finally {
            group.getIoLock().unlock();
        }
    }

    @Override
    public void deleteGroup(Group group) {
        group.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
            c.deleteOne(new Document("_id", group.getName()));
        } finally {
            group.getIoLock().unlock();
        }
    }

    @Override
    public List<HeldNode<String>> getGroupsWithPermission(Constraint constraint) {
        List<HeldNode<String>> held = new ArrayList<>();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "groups");
        try (MongoCursor<Document> cursor = c.find().iterator()) {
            while (cursor.hasNext()) {
                Document d = cursor.next();

                String holder = d.getString("_id");
                Set<NodeDataContainer> nodes = new HashSet<>(nodesFromDoc(d));
                for (NodeDataContainer e : nodes) {
                    if (!constraint.eval(e.getPermission())) {
                        continue;
                    }
                    held.add(HeldNodeImpl.of(holder, e.toNode()));
                }
            }
        }
        return held;
    }

    @Override
    public Track createAndLoadTrack(String name) {
        Track track = this.plugin.getTrackManager().getOrMake(name);
        track.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "tracks");
            try (MongoCursor<Document> cursor = c.find(new Document("_id", track.getName())).iterator()) {
                if (!cursor.hasNext()) {
                    c.insertOne(trackToDoc(track));
                } else {
                    Document d = cursor.next();
                    //noinspection unchecked
                    track.setGroups((List<String>) d.get("groups"));
                }
            }
        } finally {
            track.getIoLock().unlock();
        }
        return track;
    }

    @Override
    public Optional<Track> loadTrack(String name) {
        Track track = this.plugin.getTrackManager().getIfLoaded(name);
        if (track != null) {
            track.getIoLock().lock();
        }

        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "tracks");
            try (MongoCursor<Document> cursor = c.find(new Document("_id", name)).iterator()) {
                if (!cursor.hasNext()) {
                    return Optional.empty();
                }

                if (track == null) {
                    track = this.plugin.getTrackManager().getOrMake(name);
                    track.getIoLock().lock();
                }

                Document d = cursor.next();
                //noinspection unchecked
                track.setGroups((List<String>) d.get("groups"));
            }
        } finally {
            if (track != null) {
                track.getIoLock().unlock();
            }
        }
        return Optional.of(track);
    }

    @Override
    public void loadAllTracks() {
        List<String> tracks = new ArrayList<>();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "tracks");
        try (MongoCursor<Document> cursor = c.find().iterator()) {
            while (cursor.hasNext()) {
                String name = cursor.next().getString("_id");
                tracks.add(name);
            }
        }

        boolean success = true;
        for (String t : tracks) {
            try {
                loadTrack(t);
            } catch (Exception e) {
                e.printStackTrace();
                success = false;
            }
        }

        if (!success) {
            throw new RuntimeException("Exception occurred whilst loading a track");
        }

        TrackManager<?> tm = this.plugin.getTrackManager();
        tm.getAll().values().stream()
                .filter(t -> !tracks.contains(t.getName()))
                .forEach(tm::unload);
    }

    @Override
    public void saveTrack(Track track) {
        track.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "tracks");
            c.replaceOne(new Document("_id", track.getName()), trackToDoc(track));
        } finally {
            track.getIoLock().unlock();
        }
    }

    @Override
    public void deleteTrack(Track track) {
        track.getIoLock().lock();
        try {
            MongoCollection<Document> c = this.database.getCollection(this.prefix + "tracks");
            c.deleteOne(new Document("_id", track.getName()));
        } finally {
            track.getIoLock().unlock();
        }
    }

    @Override
    public PlayerSaveResult savePlayerData(UUID uuid, String username) {
        username = username.toLowerCase();
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "uuid");

        // find any existing mapping
        String oldUsername = getPlayerName(uuid);

        // do the insert
        if (!username.equalsIgnoreCase(oldUsername)) {
            c.replaceOne(new Document("_id", uuid), new Document("_id", uuid).append("name", username), new ReplaceOptions().upsert(true));
        }

        PlayerSaveResultImpl result = PlayerSaveResultImpl.determineBaseResult(username, oldUsername);

        Set<UUID> conflicting = new HashSet<>();
        try (MongoCursor<Document> cursor = c.find(new Document("name", username)).iterator()) {
            if (cursor.hasNext()) {
                conflicting.add(cursor.next().get("_id", UUID.class));
            }
        }
        conflicting.remove(uuid);

        if (!conflicting.isEmpty()) {
            // remove the mappings for conflicting uuids
            c.deleteMany(Filters.and(conflicting.stream().map(u -> Filters.eq("_id", u)).collect(Collectors.toList())));
            result = result.withOtherUuidsPresent(conflicting);
        }

        return result;
    }

    @Override
    public UUID getPlayerUuid(String username) {
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "uuid");
        Document doc = c.find(new Document("name", username.toLowerCase())).first();
        if (doc != null) {
            return doc.get("_id", UUID.class);
        }
        return null;
    }

    @Override
    public String getPlayerName(UUID uuid) {
        MongoCollection<Document> c = this.database.getCollection(this.prefix + "uuid");
        Document doc = c.find(new Document("_id", uuid)).first();
        if (doc != null) {
            return doc.get("name", String.class);
        }
        return null;
    }

    private static Document userToDoc(User user) {
        List<Document> nodes = user.normalData().immutable().values().stream()
                .map(NodeDataContainer::fromNode)
                .map(MongoStorage::nodeToDoc)
                .collect(Collectors.toList());

        return new Document("_id", user.getUuid())
                .append("name", user.getName().orElse("null"))
                .append("primaryGroup", user.getPrimaryGroup().getStoredValue().orElse(NodeFactory.DEFAULT_GROUP_NAME))
                .append("permissions", nodes);
    }

    private static List<NodeDataContainer> nodesFromDoc(Document document) {
        List<NodeDataContainer> nodes = new ArrayList<>();
        if (document.containsKey("permissions") && document.get("permissions") instanceof List) {
            //noinspection unchecked
            List<Document> permsList = (List<Document>) document.get("permissions");
            for (Document d : permsList) {
                nodes.add(nodeFromDoc(d));
            }
        }
        return nodes;
    }

    private static Document groupToDoc(Group group) {
        List<Document> nodes = group.normalData().immutable().values().stream()
                .map(NodeDataContainer::fromNode)
                .map(MongoStorage::nodeToDoc)
                .collect(Collectors.toList());

        return new Document("_id", group.getName()).append("permissions", nodes);
    }

    private static Document trackToDoc(Track track) {
        return new Document("_id", track.getName()).append("groups", track.getGroups());
    }

    private static Document nodeToDoc(NodeDataContainer node) {
        Document document = new Document();

        document.append("permission", node.getPermission());
        document.append("value", node.getValue());

        if (!node.getServer().equals("global")) {
            document.append("server", node.getServer());
        }

        if (!node.getWorld().equals("global")) {
            document.append("world", node.getWorld());
        }

        if (node.getExpiry() != 0L) {
            document.append("expiry", node.getExpiry());
        }

        if (!node.getContexts().isEmpty()) {
            document.append("context", contextSetToDocs(node.getContexts()));
        }

        return document;
    }

    private static NodeDataContainer nodeFromDoc(Document document) {
        String permission = document.getString("permission");
        boolean value = true;
        String server = "global";
        String world = "global";
        long expiry = 0L;
        ImmutableContextSet context = ImmutableContextSet.empty();

        if (document.containsKey("value")) {
            value = document.getBoolean("value");
        }
        if (document.containsKey("server")) {
            server = document.getString("server");
        }
        if (document.containsKey("world")) {
            world = document.getString("world");
        }
        if (document.containsKey("expiry")) {
            expiry = document.getLong("expiry");
        }

        if (document.containsKey("context") && document.get("context") instanceof List) {
            //noinspection unchecked
            List<Document> contexts = (List<Document>) document.get("context");
            context = docsToContextSet(contexts).immutableCopy();
        }

        return NodeDataContainer.of(permission, value, server, world, expiry, context);
    }

    private static List<Document> contextSetToDocs(ContextSet contextSet) {
        List<Document> contexts = new ArrayList<>(contextSet.size());
        for (Context e : contextSet) {
            contexts.add(new Document().append("key", e.getKey()).append("value", e.getValue()));
        }
        return contexts;
    }

    private static MutableContextSet docsToContextSet(List<Document> documents) {
        MutableContextSet map = MutableContextSet.create();
        for (Document doc : documents) {
            map.add(doc.getString("key"), doc.getString("value"));
        }
        return map;
    }

}
