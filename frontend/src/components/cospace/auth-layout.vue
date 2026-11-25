<template>
    <main class="co-auth">
        <section class="co-auth-story">
            <co-brand />
            <div class="co-auth-intro">
                <span class="co-eyebrow">A LITTLE SPACE. BETTER TOGETHER.</span>
                <h1>资料有归处，<br />协作有条理<span>。</span></h1>
                <p>从自己的文件，到团队的每一次交付。<br />让分享、收集与整理，都轻松一点。</p>
                <img src="../../assets/brand/together.svg" alt="两个人一起整理文件的简笔画" />
            </div>
            <div class="co-auth-notes">
                <span>01 / 存储与整理</span><span>02 / 团队与分享</span><span>03 / 收集与交付</span>
            </div>
        </section>
        <section class="co-auth-panel">
            <div class="co-auth-mobile-brand"><co-brand /></div>
            <div class="co-auth-form"><slot /></div>
            <footer>CoSpace · 给每一份资料，一个共同的空间</footer>
        </section>
    </main>
</template>
<script setup>
import CoBrand from './co-brand.vue'
</script>
<style scoped>
.co-auth {
    min-height: 100dvh;
    display: grid;
    grid-template-columns: 1.08fr 1fr;
    background: var(--co-surface);
}
.co-auth-story {
    background: #f0efe5;
    padding: 42px 7%;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    position: relative;
    border-right: 1px solid #e7e5da;
}
.co-auth-intro {
    padding-top: 24px;
}
.co-eyebrow {
    font-size: 10px;
    letter-spacing: 2.6px;
    color: #7c8776;
    font-weight: 650;
}
.co-auth-intro h1 {
    font-size: clamp(34px, 3.4vw, 54px);
    line-height: 1.35;
    letter-spacing: -1.8px;
    margin: 22px 0;
    color: #2e4437;
    font-weight: 650;
}
.co-auth-intro h1 span {
    color: var(--co-clay);
}
.co-auth-intro p {
    font-size: 15px;
    line-height: 1.9;
    color: #78806f;
}
.co-auth-intro img {
    width: min(100%, 580px);
    display: block;
    max-height: 250px;
    object-fit: contain;
    margin: 8px auto 0;
}
.co-auth-notes {
    display: flex;
    gap: 22px;
    font-size: 11px;
    color: #7c8374;
    padding-top: 16px;
    border-top: 1px solid #dcded1;
}
.co-auth-panel {
    display: flex;
    flex-direction: column;
    padding: 48px 12% 24px;
    justify-content: center;
}
.co-auth-form {
    width: 100%;
    max-width: 370px;
    margin: auto;
}
.co-auth-panel footer {
    text-align: center;
    color: #92958b;
    font-size: 11px;
    padding-top: 32px;
}
.co-auth-mobile-brand {
    display: none;
}
@media (max-width: 850px) {
    .co-auth {
        grid-template-columns: 0.9fr 1fr;
    }
    .co-auth-story {
        padding: 32px 8%;
    }
    .co-auth-intro h1 {
        font-size: 36px;
    }
    .co-auth-notes span:last-child {
        display: none;
    }
    .co-auth-panel {
        padding: 40px 9%;
    }
}
@media (max-width: 650px) {
    .co-auth {
        display: block;
        min-height: 100dvh;
    }
    .co-auth-story {
        display: none;
    }
    .co-auth-panel {
        min-height: 100dvh;
        box-sizing: border-box;
        padding: 30px 26px;
    }
    .co-auth-mobile-brand {
        display: block;
        margin-bottom: 54px;
    }
    .co-auth-form {
        padding-bottom: 30px;
    }
}
</style>
