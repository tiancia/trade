package com.trade.textgame.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface TextGameMapper {
    List<TextGameVersionRow> findPublishedCatalog();

    TextGameVersionRow findLatestPublished(@Param("storyKey") String storyKey);

    TextGameVersionRow findVersionById(@Param("id") long id);

    TextGameVersionRow findVersion(@Param("storyId") long storyId, @Param("versionNumber") int versionNumber);

    TextGameStoryRow findStoryByKey(@Param("storyKey") String storyKey);

    List<TextGameStoryRow> listStories();

    List<TextGameVersionRow> listVersions(@Param("storyId") long storyId);

    void insertStory(TextGameStoryRow row);

    void insertVersion(TextGameVersionRow row);

    int updateDraft(
            @Param("id") long id,
            @Param("storyJson") String storyJson,
            @Param("checksum") String checksum,
            @Param("expectedRevision") long expectedRevision
    );

    int archivePublished(@Param("storyId") long storyId);

    int publishDraft(
            @Param("id") long id,
            @Param("expectedRevision") long expectedRevision,
            @Param("publishedAt") Timestamp publishedAt
    );

    void updateStoryMetadata(TextGameStoryRow row);

    TextGameSessionRow findSession(@Param("sessionId") String sessionId);

    void insertSession(TextGameSessionRow row);

    int updateSession(TextGameSessionRow row);

    int deleteSession(@Param("sessionId") String sessionId);

    void insertSessionEvent(TextGameSessionEventRow row);

    List<TextGameSessionEventRow> listSessionEvents(@Param("sessionId") String sessionId);
}
