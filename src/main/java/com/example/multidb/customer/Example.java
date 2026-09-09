package com.example.multidb.customer;

import java.net.URL;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

public class Example {

    // inject the actual template
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // inject the template as ListOperations
    @Resource(name = "redisTemplate")
    private ListOperations<String, String> listOps;

    @Resource(name = "redisTemplate")
    private ValueOperations<String, String> valueOps;

    @Resource(name = "redisTemplate")
    private SetOperations<String, String> setOps;

    @Resource(name = "redisTemplate")
    private ZSetOperations<String, String> zSetOps;

    @Resource(name = "redisTemplate")
    private HashOperations<String, String, String> hashOps;

    public void addLink(String userId, URL url) {
        listOps.leftPush(userId, url.toExternalForm());
        // or use template directly
        redisTemplate.boundListOps(userId).leftPush(url.toExternalForm());
    }

    // Use a distinct Redis key for each data type.
    // Writes use injected Operations; reads show the equivalent template-bound API.
    public void setValue(String key, String value) {
        valueOps.set(key, value);
    }

    // Returns null when the key does not exist.
    public String getValue(String key) {
        return redisTemplate.boundValueOps(key).get();
    }

    // Returns 1 for a new member, 0 for an existing member.
    public Long addSetMember(String key, String member) {
        return setOps.add(key, member);
    }

    public Boolean isSetMember(String key, String member) {
        return redisTemplate.boundSetOps(key).isMember(member);
    }

    // A repeated member updates its score instead of creating another member.
    public Boolean addZSetMember(String key, String member, double score) {
        return zSetOps.add(key, member, score);
    }

    // Returns null when the member does not exist.
    public Double getZSetScore(String key, String member) {
        return redisTemplate.boundZSetOps(key).score(member);
    }

    public void putHashField(String key, String field, String value) {
        hashOps.put(key, field, value);
    }

    // Returns null when the field does not exist.
    public String getHashField(String key, String field) {
        return redisTemplate.<String, String>boundHashOps(key).get(field);
    }
}
