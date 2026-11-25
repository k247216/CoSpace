<template>
    <auth-layout>
        <span class="co-eyebrow">MAKE ROOM FOR WHAT MATTERS</span>
        <h2 class="auth-title">开启你的共同空间</h2>
        <p class="auth-description">创建账号，让资料井然有序。</p>
        <form class="auth-fields auth-fields-compact" @submit.prevent="register">
            <label for="register-username"
                >用户名<input
                    id="register-username"
                    v-model.trim="form.username"
                    autocomplete="username"
                    placeholder="6–16 位字母或数字"
                    required
            /></label>
            <label for="register-password"
                >密码<input
                    id="register-password"
                    v-model="form.password"
                    type="password"
                    autocomplete="new-password"
                    placeholder="8–16 位密码"
                    required
            /></label>
            <label for="register-confirm"
                >确认密码<input
                    id="register-confirm"
                    v-model="confirmation"
                    type="password"
                    autocomplete="new-password"
                    placeholder="再次输入密码"
                    required
            /></label>
            <div class="auth-two-fields">
                <label for="register-question"
                    >密保问题<input
                        id="register-question"
                        v-model.trim="form.question"
                        placeholder="自定义一个问题"
                        required /></label
                ><label for="register-answer"
                    >密保答案<input
                        id="register-answer"
                        v-model.trim="form.answer"
                        placeholder="用于找回密码"
                        required
                /></label>
            </div>
            <p v-if="error" class="co-error" role="alert">{{ error }}</p>
            <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading"
                >创建账号 <co-icon name="arrow"
            /></el-button>
        </form>
        <p class="auth-switch">已经有账号？<router-link to="/login">返回登录</router-link></p>
    </auth-layout>
</template>
<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthLayout from '../../components/cospace/auth-layout.vue'
import CoIcon from '../../components/cospace/co-icon.vue'
import http from '../../utils/http'
import panUtil from '../../utils/common'
const router = useRouter(),
    form = reactive({ username: '', password: '', question: '', answer: '' })
const confirmation = ref(''),
    error = ref(''),
    loading = ref(false)
async function register() {
    if (loading.value) return
    error.value = ''
    if (!panUtil.checkUsername(form.username)) {
        error.value = '用户名需为 6–16 位字母或数字'
        return
    }
    if (!panUtil.checkPassword(form.password)) {
        error.value = '密码需为 8–16 位'
        return
    }
    if (confirmation.value !== form.password) {
        error.value = '两次密码不一致'
        return
    }
    if (!form.question || !form.answer) {
        error.value = '请填写密保问题和答案'
        return
    }
    loading.value = true
    try {
        await http({ url: '/user/register', method: 'post', data: form })
        ElMessage.success('账号创建成功，请登录')
        await router.replace('/login')
    } catch (failure) {
        error.value = failure.message || '注册失败，请重试'
    } finally {
        loading.value = false
    }
}
</script>
