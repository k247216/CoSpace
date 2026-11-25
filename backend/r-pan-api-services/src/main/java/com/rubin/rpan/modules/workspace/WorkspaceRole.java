package com.rubin.rpan.modules.workspace;

public enum WorkspaceRole {
  OWNER,
  ADMIN,
  MEMBER,
  VIEWER;

  public boolean managesMembers() {
    return this == OWNER || this == ADMIN;
  }

  public boolean canAssign(WorkspaceRole current, WorkspaceRole requested) {
    if (!managesMembers() || current == OWNER || requested == OWNER) return false;
    return this == OWNER || (current != ADMIN && requested != ADMIN);
  }
}
