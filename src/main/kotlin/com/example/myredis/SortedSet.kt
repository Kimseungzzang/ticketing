package com.example.myredis

import kotlin.random.Random

// Redis식 sorted set — skip list + member→score 해시.
//   · skip list: 정렬 유지 + 각 노드가 span(다음 노드까지 건너뛴 원소 수)을 들어 **ZRANK가 O(logN)**.
//     (heap이면 임의 원소 rank가 O(N)이라 대기열 폴링(순번 확인)에 최악 → 그 차이를 보려고 이걸 만든다)
//   · 해시(dict): member→score를 O(1)로 (ZSCORE, 존재확인).
//   score 오름차순, 동점이면 member 사전순 (Redis와 동일). 스레드 안전은 상위(Store)에서 동기화.
class SortedSet {
    private companion object {
        const val MAX_LEVEL = 32
        const val P = 0.25
    }

    private class Node(val member: String, val score: Double, level: Int) {
        val forward = arrayOfNulls<Node>(level)   // 레벨별 다음 노드
        val span = LongArray(level)               // 레벨별 건너뛰는 원소 수 (rank 계산용)
    }

    private val head = Node("", Double.NEGATIVE_INFINITY, MAX_LEVEL)
    private var level = 1
    private var length = 0L
    private val dict = HashMap<String, Double>()

    private fun randomLevel(): Int {
        var lvl = 1
        while (Random.nextDouble() < P && lvl < MAX_LEVEL) lvl++
        return lvl
    }

    // (s1,m1) < (s2,m2) ?  — score 우선, 동점이면 member 사전순
    private fun before(s1: Double, m1: String, s2: Double, m2: String): Boolean =
        s1 < s2 || (s1 == s2 && m1 < m2)

    val size: Int get() = length.toInt()

    fun score(member: String): Double? = dict[member]

    /** ZADD — 새로 추가면 true, 기존 갱신이면 false. */
    fun add(member: String, score: Double): Boolean {
        val old = dict[member]
        if (old != null) {
            if (old == score) return false
            zslDelete(old, member)
        }
        zslInsert(score, member)
        dict[member] = score
        return old == null
    }

    /** ZREM — 있으면 true. */
    fun remove(member: String): Boolean {
        val s = dict.remove(member) ?: return false
        zslDelete(s, member)
        return true
    }

    /** ZRANK — 0-indexed 순위, 없으면 null. (핵심: O(logN)) */
    fun rank(member: String): Long? {
        val score = dict[member] ?: return null
        var x = head
        var rank = 0L
        for (i in level - 1 downTo 0) {
            while (x.forward[i] != null &&
                before(x.forward[i]!!.score, x.forward[i]!!.member, score, member)) {
                rank += x.span[i]
                x = x.forward[i]!!
            }
        }
        // 다음 노드가 target
        val next = x.forward[0]
        return if (next != null && next.member == member) rank else null
    }

    /** ZRANGE start..end (0-indexed, 포함). 음수 인덱스 지원. member 리스트 반환. */
    fun rangeByRank(startIn: Long, endIn: Long): List<Pair<String, Double>> {
        if (length == 0L) return emptyList()
        var start = if (startIn < 0) length + startIn else startIn
        var end = if (endIn < 0) length + endIn else endIn
        if (start < 0) start = 0
        if (end >= length) end = length - 1
        if (start > end) return emptyList()

        var x = head
        var traversed = 0L
        for (i in level - 1 downTo 0) {
            while (x.forward[i] != null && traversed + x.span[i] <= start) {
                traversed += x.span[i]
                x = x.forward[i]!!
            }
        }
        // x는 rank(start-1) 위치, x.forward[0]이 start
        val result = ArrayList<Pair<String, Double>>()
        var node = x.forward[0]
        var r = start
        while (node != null && r <= end) {
            result.add(node.member to node.score)
            node = node.forward[0]
            r++
        }
        return result
    }

    /** ZPOPMIN — 최소 score n개 제거하며 반환. */
    fun popMin(n: Int): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(n)
        repeat(n) {
            val first = head.forward[0] ?: return out
            zslDelete(first.score, first.member)
            dict.remove(first.member)
            out.add(first.member to first.score)
        }
        return out
    }

    // ── skip list 내부 (Redis t_zset.c 알고리즘 포팅) ──

    private fun zslInsert(score: Double, member: String) {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        val rank = LongArray(MAX_LEVEL)
        var x = head
        for (i in level - 1 downTo 0) {
            rank[i] = if (i == level - 1) 0 else rank[i + 1]
            while (x.forward[i] != null &&
                before(x.forward[i]!!.score, x.forward[i]!!.member, score, member)) {
                rank[i] += x.span[i]
                x = x.forward[i]!!
            }
            update[i] = x
        }
        val lvl = randomLevel()
        if (lvl > level) {
            for (i in level until lvl) {
                rank[i] = 0
                update[i] = head
                head.span[i] = length
            }
            level = lvl
        }
        val node = Node(member, score, lvl)
        for (i in 0 until lvl) {
            node.forward[i] = update[i]!!.forward[i]
            update[i]!!.forward[i] = node
            node.span[i] = update[i]!!.span[i] - (rank[0] - rank[i])
            update[i]!!.span[i] = (rank[0] - rank[i]) + 1
        }
        for (i in lvl until level) {
            update[i]!!.span[i]++
        }
        length++
    }

    private fun zslDelete(score: Double, member: String) {
        val update = arrayOfNulls<Node>(MAX_LEVEL)
        var x = head
        for (i in level - 1 downTo 0) {
            while (x.forward[i] != null &&
                before(x.forward[i]!!.score, x.forward[i]!!.member, score, member)) {
                x = x.forward[i]!!
            }
            update[i] = x
        }
        x = x.forward[0] ?: return
        if (x.score != score || x.member != member) return
        for (i in 0 until level) {
            if (update[i]!!.forward[i] === x) {
                update[i]!!.span[i] += x.span[i] - 1
                update[i]!!.forward[i] = x.forward[i]
            } else {
                update[i]!!.span[i]--
            }
        }
        while (level > 1 && head.forward[level - 1] == null) level--
        length--
    }
}
