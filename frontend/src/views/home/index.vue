<template>
    <section class="home-page">
        <div class="home-hero">
            <div class="home-copy">
                <span class="home-kicker">一个共同的空间</span>
                <h1>把资料放好，<br />把协作变简单<span>。</span></h1>
                <p>从个人文件到团队交付，<br />在 CoSpace，存储、分享和收集都有清晰的归处。</p>
                <div class="home-actions">
                    <router-link class="home-primary" to="/files"
                        >打开我的文件 <co-icon name="arrow" /></router-link
                    ><router-link class="home-secondary" to="/workspaces"
                        >进入团队空间 <co-icon name="arrow"
                    /></router-link>
                </div>
            </div>
            <img
                class="home-illustration"
                src="../../assets/brand/together.svg"
                alt="两个人一起整理文件的简笔画"
            />
        </div>
        <div class="home-entries">
            <router-link v-for="entry in entries" :key="entry.to" :to="entry.to" class="home-entry"
                ><span class="home-entry-icon"><co-icon :name="entry.icon" /></span>
                <h2>{{ entry.title }}</h2>
                <p>{{ entry.description }}</p>
                <span class="home-entry-action">{{ entry.action }} <co-icon name="arrow" /></span
            ></router-link>
        </div>
        <div class="home-bottom">
            <span>个人资料妥善收纳 · 团队权限清晰管理 · 每次交付有迹可循</span
            ><router-link to="/collections">查看收到的收集邀请 →</router-link>
        </div>
    </section>
</template>
<script setup>
import CoIcon from '../../components/cospace/co-icon.vue'
const entries = [
    {
        to: '/files',
        icon: 'folder',
        title: '给文件一个归处',
        description: '上传与整理个人资料，按文件类型快速找到需要的内容。',
        action: '整理我的文件',
    },
    {
        to: '/workspaces',
        icon: 'users',
        title: '给团队一个空间',
        description: '共同存放资料，按角色分配权限，向指定的人分享文件。',
        action: '打开团队空间',
    },
    {
        to: '/collections',
        icon: 'inbox',
        title: '给交付一个结果',
        description: '接收资料邀请、提交文件，团队按人核对进度，让每次交付清晰可查。',
        action: '查看我的收集',
    },
]
</script>
<style scoped>
.home-page {
    height: 100%;
    min-height: 0;
    max-width: 1240px;
    margin: auto;
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto auto;
}
.home-hero {
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr 1fr;
    align-items: center;
    gap: 24px;
    padding: clamp(12px, 2.5vh, 26px) 20px;
    border-bottom: 1px solid var(--co-line);
}
.home-kicker {
    font-size: 11px;
    color: #91a07f;
}
.home-copy h1 {
    font-size: clamp(28px, 4.5vh, 46px);
    line-height: 1.35;
    letter-spacing: -1.4px;
    font-weight: 600;
    margin: 14px 0;
    color: #344e3c;
}
.home-copy h1 span {
    color: var(--co-clay);
}
.home-copy > p {
    font-size: 12px;
    line-height: 1.8;
    color: #869178;
    margin: 0;
}
.home-actions {
    display: flex;
    align-items: center;
    gap: 22px;
    flex-wrap: wrap;
    margin-top: 22px;
    font-size: 12px;
}
.home-primary,
.home-secondary {
    display: flex;
    align-items: center;
    gap: 12px;
}
.home-primary {
    background: var(--co-accent);
    color: #fffdf7;
    padding: 11px 17px;
    border-radius: 7px;
}
.home-primary:hover {
    background: #34503e;
    color: #fff;
}
.home-secondary {
    color: #70815f;
}
.home-actions svg {
    width: 14px;
    height: 14px;
}
.home-illustration {
    width: 100%;
    height: 100%;
    max-height: 300px;
    object-fit: contain;
    min-height: 0;
}
.home-entries {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px;
    padding: clamp(14px, 2.5vh, 24px) 0;
}
.home-entry {
    padding: clamp(15px, 2vh, 23px);
    background: var(--co-surface);
    border: 1px solid var(--co-line);
    border-radius: 10px;
    color: var(--co-ink);
    transition: border-color 0.2s;
}
.home-entry:hover {
    border-color: #b5c3a7;
}
.home-entry-icon {
    display: grid;
    place-items: center;
    width: 32px;
    height: 32px;
    border-radius: 8px;
    background: #eaf0df;
    color: #82956c;
    margin-bottom: 14px;
}
.home-entry-icon svg {
    width: 18px;
    height: 18px;
}
.home-entry h2 {
    font-size: 15px;
    font-weight: 550;
    letter-spacing: 0;
    margin: 0 0 8px;
}
.home-entry p {
    font-size: 11px;
    line-height: 1.8;
    color: #8c9580;
    min-height: 40px;
    margin: 0;
}
.home-entry-action {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
    color: #69815a;
    margin-top: 16px;
}
.home-entry-action svg {
    width: 14px;
    height: 14px;
}
.home-bottom {
    display: flex;
    justify-content: space-between;
    gap: 14px;
    font-size: 10px;
    color: #9aa38f;
    line-height: 1.8;
    padding: 0 3px 8px;
}
.home-bottom a {
    color: #8a9a77;
    white-space: nowrap;
}
@media (max-height: 700px) {
    .home-kicker {
        display: none;
    }
    .home-copy h1 {
        font-size: 28px;
        margin: 8px 0;
    }
    .home-copy > p {
        font-size: 11px;
    }
    .home-actions {
        margin-top: 13px;
    }
    .home-entry {
        padding: 14px;
    }
    .home-entry-icon {
        width: 27px;
        height: 27px;
        margin-bottom: 9px;
    }
    .home-entry-action {
        margin-top: 10px;
    }
    .home-bottom {
        font-size: 9px;
    }
}
@media (max-width: 760px) {
    .home-hero {
        grid-template-columns: 1fr;
        position: relative;
        padding: 10px 0 17px;
        gap: 0;
    }
    .home-copy {
        position: relative;
        z-index: 1;
    }
    .home-copy h1 {
        font-size: clamp(27px, 4vh, 35px);
    }
    .home-illustration {
        position: absolute;
        right: 0;
        top: 15px;
        width: 39%;
        height: 160px;
        opacity: 0.16;
        pointer-events: none;
    }
    .home-copy > p {
        font-size: 11px;
    }
    .home-actions {
        gap: 18px;
        margin-top: 17px;
        font-size: 11px;
    }
    .home-primary {
        padding: 10px 14px;
    }
    .home-entries {
        grid-template-columns: 1fr;
        gap: 10px;
        padding: 15px 0;
    }
    .home-entry {
        display: grid;
        grid-template-columns: 32px minmax(0, 1fr) 14px;
        grid-template-rows: auto auto;
        column-gap: 12px;
        row-gap: 4px;
        padding: 13px;
    }
    .home-entry-icon {
        grid-row: 1/3;
        margin: 0;
        align-self: center;
    }
    .home-entry h2 {
        font-size: 13px;
        margin: 0;
    }
    .home-entry p {
        font-size: 10px;
        line-height: 1.7;
        min-height: 0;
    }
    .home-entry-action {
        grid-row: 1/3;
        grid-column: 3;
        font-size: 0;
        margin: 0;
        align-self: center;
    }
    .home-bottom {
        display: block;
        font-size: 9px;
        padding-bottom: 0;
    }
    .home-bottom > span {
        display: none;
    }
}
@media (max-width: 760px) and (max-height: 700px) {
    .home-hero {
        padding-bottom: 10px;
    }
    .home-copy h1 {
        font-size: 26px;
        margin: 8px 0;
    }
    .home-actions {
        margin-top: 11px;
    }
    .home-entries {
        padding: 11px 0;
        gap: 8px;
    }
    .home-entry {
        padding: 10px 12px;
    }
    .home-illustration {
        height: 120px;
    }
    .home-entry p {
        line-height: 1.6;
    }
}
</style>
