<template>
    <div class="co-shell">
        <pan-navbar :open="navigationOpen" @close="navigationOpen = false" /><pan-header
            @toggle-menu="navigationOpen = !navigationOpen"
        /><pan-app-main /><button
            v-if="navigationOpen"
            class="co-nav-backdrop"
            aria-label="关闭导航"
            @click="navigationOpen = false"
        />
    </div>
</template>
<script setup>
import { ref } from 'vue'
import PanNavbar from '../components/navbar/index.vue'
import PanHeader from '../components/header/index.vue'
import PanAppMain from '../components/app-main/index.vue'
const navigationOpen = ref(false)
</script>
