<template>
    <div class="user-space" aria-label="用户空间">
        <div class="space-title">
            个人容量
            <button class="space-refresh" aria-label="刷新个人容量" title="刷新容量" @click="load">
                <co-icon name="refresh" />
            </button>
        </div>
        <capacity-bar
            v-if="space"
            :used="space.usedBytes"
            :reserved="space.reservedBytes"
            :total="space.totalBytes"
            label="个人容量占用"
        />
        <p v-if="error" role="alert">{{ error }}</p>
    </div>
</template>
<script>
import http from '../../utils/http'
import CapacityBar from '../cospace/capacity-bar.vue'
import CoIcon from '../cospace/co-icon.vue'
export default {
    name: 'UserSpace',
    components: { CapacityBar, CoIcon },
    data() {
        return { space: null, error: '' }
    },
    mounted() {
        this.load()
        window.addEventListener('rpan-space-changed', this.schedule)
    },
    beforeUnmount() {
        clearTimeout(this.timer)
        window.removeEventListener('rpan-space-changed', this.schedule)
    },
    watch: {
        $route() {
            this.schedule()
        },
    },
    methods: {
        schedule() {
            clearTimeout(this.timer)
            this.timer = setTimeout(() => this.load(), 200)
        },
        async load() {
            try {
                this.space = (await http.get('/file/space')).data
                this.error = ''
            } catch (error) {
                this.error = error.message || '空间信息暂不可用'
            }
        },
    },
}
</script>
<style scoped>
.user-space {
    margin: 22px 3px 0;
    padding: 17px 13px;
    background: #e6eadd;
    border: 1px solid #dfe4d3;
    border-radius: 10px;
    color: #7b866e;
}
.space-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-weight: 550;
    font-size: 11px;
    margin-bottom: 11px;
    color: #58694b;
}
.space-refresh {
    border: 0;
    background: transparent;
    color: #7e906e;
    padding: 0;
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
}
.space-refresh svg {
    width: 13px;
    height: 13px;
}
.user-space p {
    font-size: 10px;
    color: #a9604b;
    margin: 10px 0 0;
}
</style>
