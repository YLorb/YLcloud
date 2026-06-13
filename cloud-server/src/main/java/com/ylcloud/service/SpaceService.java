package com.ylcloud.service;

import com.ylcloud.DTO.SpaceCreateDTO;
import com.ylcloud.DTO.SpaceUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceVO;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间基础业务服务。
 */
@Service
public class SpaceService {
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper spaceMemberMapper;
    private final SpaceFileMapper spaceFileMapper;
    private final SpaceRagMapper spaceRagMapper;
    private final SpacePermissionService spacePermissionService;

    public SpaceService(SpaceMapper spaceMapper,
                        SpaceMemberMapper spaceMemberMapper,
                        SpaceFileMapper spaceFileMapper,
                        SpaceRagMapper spaceRagMapper,
                        SpacePermissionService spacePermissionService) {
        this.spaceMapper = spaceMapper;
        this.spaceMemberMapper = spaceMemberMapper;
        this.spaceFileMapper = spaceFileMapper;
        this.spaceRagMapper = spaceRagMapper;
        this.spacePermissionService = spacePermissionService;
    }

    /**
     * 为新用户创建默认个人空间。
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @return 默认个人空间
     */
    @Transactional
    public Space createDefaultPersonalSpace(Long userId, String username) {
        String spaceName = username == null || username.isBlank() ? "我的空间" : username + "的空间";
        return createSpaceInternal(userId,spaceName,"默认个人空间",SpaceConstant.TYPE_PERSONAL);
    }

    /**
     * 创建团队空间。
     *
     * @param dto 创建空间请求参数
     * @param userId 当前用户 ID
     * @return 创建后的空间展示对象
     */
    @Transactional
    public SpaceVO createSpace(SpaceCreateDTO dto, Long userId) {
        Space space = createSpaceInternal(userId,dto.getName(),dto.getDescription(),SpaceConstant.TYPE_TEAM);
        SpaceVO vo = toSpaceVO(space);
        vo.setRole(SpaceConstant.ROLE_OWNER);
        return vo;
    }

    public List<SpaceVO> listMySpaces(Long userId) {
        return spaceMapper.listByUserId(userId);
    }

    public SpaceVO getSpace(Long spaceId, Long userId) {
        SpaceMember member = spacePermissionService.requireMember(spaceId,userId);
        Space space = requireSpace(spaceId);
        SpaceVO vo = toSpaceVO(space);
        vo.setRole(member.getRole());
        return vo;
    }

    @Transactional
    public SpaceVO updateSpace(Long spaceId, SpaceUpdateDTO dto, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        int rows = spaceMapper.updateInfo(spaceId,dto.getName(),dto.getDescription(),LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("空间更新失败");
        }
        return getSpace(spaceId,userId);
    }

    @Transactional
    public Boolean deleteSpace(Long spaceId, Long userId) {
        spacePermissionService.requireOwner(spaceId,userId);
        int rows = spaceMapper.disable(spaceId,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("空间删除失败");
        }
        return true;
    }

    public Space requireSpace(Long spaceId) {
        Space space = spaceMapper.getById(spaceId);
        if(space == null) {
            throw new BaseException("空间不存在");
        }
        return space;
    }

    @Transactional
    public SpaceVO updateVersionEnabled(Long spaceId, Integer versionEnabled, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        if(!StatusConstant.ENABLE.equals(versionEnabled) && !StatusConstant.DISABLE.equals(versionEnabled)) {
            throw new BaseException("历史版本开关只能为 1 或 0");
        }
        int rows = spaceMapper.updateVersionEnabled(spaceId,versionEnabled,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("空间历史版本设置更新失败");
        }
        return getSpace(spaceId,userId);
    }

    private Space createSpaceInternal(Long ownerId, String name, String description, String type) {
        LocalDateTime now = LocalDateTime.now();
        Space space = new Space();
        space.setName(name);
        space.setDescription(description);
        space.setType(type);
        space.setOwnerId(ownerId);
        space.setRagStatus(StatusConstant.ENABLE);
        space.setVersionEnabled(StatusConstant.ENABLE);
        space.setStatus(StatusConstant.ENABLE);
        space.setCreatetime(now);
        space.setUpdatetime(now);
        spaceMapper.insert(space);

        SpaceFile root = new SpaceFile();
        root.setSpaceId(space.getId());
        root.setFileName("/");
        root.setDir(1);
        root.setParentId(0L);
        root.setPath("/");
        root.setStatus(StatusConstant.ENABLE);
        root.setCreatedBy(ownerId);
        root.setCreatetime(now);
        root.setUpdatetime(now);
        spaceFileMapper.insert(root);
        spaceMapper.updateRootDir(space.getId(),root.getId(),now);
        space.setRootDirId(root.getId());

        SpaceMember owner = new SpaceMember();
        owner.setSpaceId(space.getId());
        owner.setUserId(ownerId);
        owner.setRole(SpaceConstant.ROLE_OWNER);
        owner.setStatus(StatusConstant.ENABLE);
        owner.setCreatetime(now);
        owner.setUpdatetime(now);
        spaceMemberMapper.insert(owner);

        SpaceRagConfig ragConfig = new SpaceRagConfig();
        ragConfig.setSpaceId(space.getId());
        ragConfig.setEmbeddingModel("langchain4j-ready");
        ragConfig.setChatModel("langchain4j-ready");
        ragConfig.setVectorCollection("space_" + space.getId() + "_rag");
        ragConfig.setChunkSize(1000);
        ragConfig.setChunkOverlap(100);
        ragConfig.setTopK(5);
        ragConfig.setScoreThreshold(BigDecimal.ZERO);
        ragConfig.setEnabled(StatusConstant.ENABLE);
        ragConfig.setStatus(StatusConstant.ENABLE);
        ragConfig.setCreatetime(now);
        ragConfig.setUpdatetime(now);
        spaceRagMapper.insert(ragConfig);
        return space;
    }

    private SpaceVO toSpaceVO(Space space) {
        SpaceVO vo = new SpaceVO();
        vo.setId(space.getId());
        vo.setName(space.getName());
        vo.setDescription(space.getDescription());
        vo.setType(space.getType());
        vo.setOwnerId(space.getOwnerId());
        vo.setRootDirId(space.getRootDirId());
        vo.setRagStatus(space.getRagStatus());
        vo.setVersionEnabled(space.getVersionEnabled());
        vo.setCreatetime(space.getCreatetime());
        vo.setUpdatetime(space.getUpdatetime());
        return vo;
    }
}
