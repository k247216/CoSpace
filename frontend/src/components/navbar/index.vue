<template>
    <aside class="co-sidebar" :class="{ 'is-open': open }">
        <div class="co-sidebar-brand">
            <co-brand @click="$emit('close')" /><button
                class="co-mobile-close"
                aria-label="关闭导航"
                @click="$emit('close')"
            >
                <co-icon name="close" />
            </button>
        </div>
        <nav aria-label="主导航">
            <router-link
                v-for="item in main"
                :key="item.to"
                :to="item.to"
                class="co-nav-item"
                :class="{ 'is-active': active(item.to) }"
                @click="$emit('close')"
                ><co-icon :name="item.icon" /><span>{{ item.label }}</span></router-link
            >
            <span class="co-nav-label co-nav-label-files">按类型浏览</span>
            <router-link
                v-for="item in types"
                :key="item.to"
                :to="item.to"
                class="co-nav-item co-nav-secondary"
                :class="{ 'is-active': active(item.to) }"
                @click="$emit('close')"
                ><co-icon :name="item.icon" /><span>{{ item.label }}</span></router-link
            >
            <router-link
                to="/recycles"
                class="co-nav-item co-recycle-link"
                :class="{ 'is-active': active('/recycles') }"
                @click="$emit('close')"
                ><co-icon name="trash" /><span>回收站</span></router-link
            >
        </nav>
        <user-space />
    </aside>
</template>
<script setup>
import { useRoute } from 'vue-router'
import CoBrand from '../cospace/co-brand.vue'
import CoIcon from '../cospace/co-icon.vue'
import UserSpace from '../user-space/index.vue'
defineProps({ open: Boolean })
defineEmits(['close'])
const route = useRoute()
const main = [
    { to: '/files', icon: 'folder', label: '全部文件' },
    { to: '/workspaces', icon: 'users', label: '团队空间' },
    { to: '/collections', icon: 'inbox', label: '我的收集' },
    { to: '/shares', icon: 'share', label: '我的分享' },
]
const types = [
    { to: '/imgs', icon: 'image', label: '图片' },
    { to: '/docs', icon: 'file', label: '文档' },
    { to: '/videos', icon: 'video', label: '视频' },
    { to: '/musics', icon: 'music', label: '音乐' },
]
const active = (path) =>
    route.path === path || (path === '/collections' && route.path.startsWith('/collections/'))
</script>
