package com.github.ttereshchenko.mailkit.pst;

/**
 * An entry in the Node Database's Node B-Tree (NBT): the node id, the block id of its data, the
 * block id of its sub-node map ({@code 0} if none) and its parent node id ([MS-PST] §2.2.2.7.7.4).
 */
public record NodeEntry(int nodeId, long dataBid, long subBid, int parentNodeId) {}
