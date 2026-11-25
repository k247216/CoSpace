<template>
    <header class="co-header">
        <div class="co-header-left">
            <button class="co-mobile-menu" aria-label="打开导航" @click="$emit('toggle-menu')">
                <co-icon name="menu" />
            </button>
        </div>
        <el-dropdown @command="command"
            ><button class="co-account" aria-label="账号菜单">
                <span>{{ username || '我的账号' }}</span><co-icon
                    class="co-account-chevron"
                    name="down"
                /></button
            ><template #dropdown
                ><el-dropdown-menu
                    ><el-dropdown-item command="password">修改密码</el-dropdown-item
                    ><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu
                ></template
            ></el-dropdown
        >
    </header>
    <el-dialog v-model="editing" title="修改密码" width="440px" :close-on-click-modal="false">
        <el-form label-position="top" @submit.prevent="changePassword"
            ><el-form-item label="旧密码"
                ><el-input
                    v-model="password"
                    type="password"
                    show-password
                    autocomplete="current-password" /></el-form-item
            ><el-form-item label="新密码"
                ><el-input
                    v-model="nextPassword"
                    type="password"
                    show-password
                    autocomplete="new-password" /></el-form-item
            ><el-form-item label="确认新密码"
                ><el-input
                    v-model="confirmation"
                    type="password"
                    show-password
                    autocomplete="new-password"
            /></el-form-item>
            <p v-if="error" class="co-error" role="alert">{{ error }}</p></el-form
        ><template #footer
            ><el-button @click="editing = false">取消</el-button
            ><el-button type="primary" :loading="saving" @click="changePassword"
                >保存密码</el-button
            ></template
        >
    </el-dialog>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import CoIcon from '../cospace/co-icon.vue'
import http from '../../utils/http'
import { clearToken } from '../../utils/cookie'
import panUtil from '../../utils/common'
defineEmits(['toggle-menu'])
const username = ref(''),
    editing = ref(false),
    saving = ref(false),
    error = ref('')
const password = ref(''),
    nextPassword = ref(''),
    confirmation = ref('')
async function command(value) {
    if (value === 'password') {
        password.value = ''
        nextPassword.value = ''
        confirmation.value = ''
        error.value = ''
        editing.value = true
        return
    }
    try {
        await ElMessageBox.confirm('确定退出当前账号？', '退出登录', {
            confirmButtonText: '退出登录',
            cancelButtonText: '取消',
        })
    } catch {
        return
    }
    try {
        await http({ url: '/user/exit', method: 'post' })
        clearToken()
        window.location.assign('/login')
    } catch (failure) {
        ElMessage.error(failure.message)
    }
}
async function changePassword() {
    if (saving.value) return
    error.value = ''
    if (!panUtil.checkPassword(password.value) || !panUtil.checkPassword(nextPassword.value)) {
        error.value = '旧密码和新密码均需为 8–16 位'
        return
    }
    if (nextPassword.value !== confirmation.value) {
        error.value = '两次新密码不一致'
        return
    }
    saving.value = true
    try {
        await http({
            url: '/user/password/change',
            method: 'post',
            data: { password: password.value, newPassword: nextPassword.value },
        })
        clearToken()
        window.location.assign('/login')
    } catch (failure) {
        error.value = failure.message
    } finally {
        saving.value = false
    }
}
onMounted(async () => {
    try {
        username.value = (await http({ url: '/user' })).data.username
    } catch (failure) {
        ElMessage.error(failure.message)
    }
})
</script>
