package com.rubin.rpan.modules.file.vo;

import com.google.common.collect.Lists;
import com.rubin.rpan.modules.file.entity.RPanUserFile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 文件夹信息返回实体 */
@Schema(description = "文件夹信息返回实体")
public class FolderTreeNode implements Serializable {

  private static final long serialVersionUID = 3942552174021452303L;

  /** 节点显示标题 */
  @Schema(description = "节点显示标题")
  private String label;

  /** 唯一标识 */
  @JsonSerialize(using = ToStringSerializer.class)
  @Schema(description = "唯一标识")
  private Long id;

  /** 子节点 */
  @Schema(description = "子节点")
  private List<FolderTreeNode> children;

  /** 父文件夹ID */
  @JsonSerialize(using = ToStringSerializer.class)
  @Schema(description = "父文件夹ID")
  private Long parentId;

  /**
   * 拼装树节点
   *
   * @param rPanUserFile
   * @return
   */
  public static FolderTreeNode assembleFolderTreeNode(RPanUserFile rPanUserFile) {
    FolderTreeNode folderTreeNode = new FolderTreeNode();
    folderTreeNode.setLabel(rPanUserFile.getFilename());
    folderTreeNode.setId(rPanUserFile.getFileId());
    folderTreeNode.setChildren(Lists.newArrayList());
    folderTreeNode.setParentId(rPanUserFile.getParentId());
    return folderTreeNode;
  }

  public FolderTreeNode() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public List<FolderTreeNode> getChildren() {
    return children;
  }

  public void setChildren(List<FolderTreeNode> children) {
    this.children = children;
  }

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    FolderTreeNode that = (FolderTreeNode) o;
    return Objects.equals(label, that.label)
        && Objects.equals(id, that.id)
        && Objects.equals(children, that.children)
        && Objects.equals(parentId, that.parentId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, id, children, parentId);
  }

  @Override
  public String toString() {
    return "FolderTreeNode{"
        + "label='"
        + label
        + '\''
        + ", id="
        + id
        + ", children="
        + children
        + ", parentId="
        + parentId
        + '}';
  }
}
