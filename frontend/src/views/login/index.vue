<template>
    <auth-layout>
        <span class="co-eyebrow">WELCOME BACK</span>
        <h2 class="auth-title">欢迎回到 CoSpace</h2>
        <p class="auth-description">登录，继续整理你的灵感与资料。</p>
        <form class="auth-fields" @submit.prevent="login">
            <label for="login-username"
                >用户名<input
                    id="login-username"
                    ref="usernameInput"
                    v-model.trim="username"
                    autocomplete="username"
                    placeholder="输入你的用户名"
                    required
            /></label>
            <label for="login-password"
                >密码<input
                    id="login-password"
                    v-model="password"
                    type="password"
                    autocomplete="current-password"
                    placeholder="输入密码"
                    required
            /></label>
            <div class="auth-link-row">
                <span>你的文件，安心存放。</span><router-link to="/forget">忘记密码？</router-link>
            </div>
            <p v-if="error" class="co-error" role="alert">{{ error }}</p>
            <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading"
                >登录 <co-icon name="arrow"
            /></el-button>
        </form>
        <p class="auth-switch">
            还没有账号？<router-link to="/register">创建 CoSpace 账号</router-link>
        </p>
        <div class="auth-footnote">
            <span class="status-dot" />一个账号，连接你的个人空间与团队资料。
        </div>
    </auth-layout>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import AuthLayout from '../../components/cospace/auth-layout.vue'
import CoIcon from '../../components/cospace/co-icon.vue'
import http from '../../utils/http'
import panUtil from '../../utils/common'
import { setToken } from '../../utils/cookie'
const router = useRouter(),
    route = useRoute()
const username = ref(''),
    password = ref(''),
    error = ref(''),
    loading = ref(false),
    usernameInput = ref(null)
async function login() {
    if (loading.value) return
    error.value = ''
    if (!panUtil.checkUsername(username.value)) {
        error.value = '用户名需为 6–16 位字母或数字'
        return
    }
    if (!panUtil.checkPassword(password.value)) {
        error.value = '密码需为 8–16 位'
        return
    }
    loading.value = true
    try {
        const result = await http({
            url: '/user/login',
            method: 'post',
            data: { username: username.value, password: password.value },
        })
        setToken(result.data)
        const target = route.query.redirect
        await router.replace(
            typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
                ? target
                : '/files',
        )
    } catch (failure) {
        error.value = failure.message || '登录失败，请稍后再试'
    } finally {
        loading.value = false
    }
}
onMounted(() => usernameInput.value?.focus())
</script>
