package com.example.myapplication

/**
 * 统一管理悬浮小人的逻辑目录名。
 *
 * 当前图片物理位置位于 `res/drawable/avatar` 与 `res/drawable/avatar1`，
 * 构建时会被打包为可按路径读取的原始文件；这里保存的是业务层使用的目录标识。
 */
object AvatarAssets {
    const val DIR_AVATAR = "avatar"
    const val DIR_AVATAR1 = "avatar1"
}

