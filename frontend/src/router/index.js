import { createRouter, createWebHistory } from 'vue-router'
import Docs from '../views/list-page/doc/index.vue'
import Files from '../views/list-page/file/index.vue'
import Imgs from '../views/list-page/img/index.vue'
import Musics from '../views/list-page/music/index.vue'
import Recycles from '../views/list-page/recycle/index.vue'
import Shares from '../views/list-page/share/index.vue'
import Videos from '../views/list-page/video/index.vue'
import Layout from '../layout/index.vue'
import Login from '../views/login/index.vue'
import Register from '../views/register/index.vue'
import Forget from '../views/forget/index.vue'
import Share from '../views/share/index.vue'
import PreviewCode from '../views/preview/code/index.vue'
import PreviewOffice from '../views/preview/office/index.vue'
import PreviewIframe from '../views/preview/iframe/index.vue'
import PreviewMusic from '../views/preview/music/index.vue'
import PreviewVideo from '../views/preview/video/index.vue'
import Error404 from '../views/error-page/404/index.vue'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
NProgress.configure({ showSpinner: false })
import { getToken } from '../utils/cookie'

const routes = [
    {
        path: '/',
        name: 'Index',
        component: Layout,
        children: [
            { path: '', name: 'Home', component: () => import('../views/home/index.vue') },
            {
                path: '/workspaces',
                name: 'Workspaces',
                component: () => import('../views/workspaces/index.vue'),
            },
            {
                path: '/collections',
                name: 'Collections',
                component: () => import('../views/workspaces/collections.vue'),
            },
            {
                path: '/collections/:collection',
                name: 'Collection',
                component: () => import('../views/workspaces/collection.vue'),
            },
            {
                path: '/team-shares/:share',
                name: 'TeamShare',
                component: () => import('../views/workspaces/share.vue'),
            },
            {
                path: '/docs',
                name: 'Docs',
                component: Docs,
            },
            {
                path: '/files',
                name: 'Files',
                component: Files,
            },
            {
                path: '/imgs',
                name: 'Imgs',
                component: Imgs,
            },
            {
                path: '/musics',
                name: 'Musics',
                component: Musics,
            },
            {
                path: '/recycles',
                name: 'Recycles',
                component: Recycles,
            },
            {
                path: '/shares',
                name: 'Shares',
                component: Shares,
            },
            {
                path: '/videos',
                name: 'Videos',
                component: Videos,
            },
        ],
    },
    {
        path: '/login',
        name: 'Login',
        component: Login,
    },
    {
        path: '/register',
        name: 'Register',
        component: Register,
    },
    {
        path: '/forget',
        name: 'Forget',
        component: Forget,
    },
    {
        path: '/share/:shareId',
        name: 'Share',
        component: Share,
    },
    {
        path: '/preview/code/:fileId',
        name: 'PreviewCode',
        component: PreviewCode,
    },
    {
        path: '/preview/office/:fileId',
        name: 'PreviewOffice',
        component: PreviewOffice,
    },
    {
        path: '/preview/iframe/:fileId',
        name: 'PreviewIframe',
        component: PreviewIframe,
    },
    {
        path: '/preview/music/:parentId/:fileId',
        name: 'PreviewMusic',
        component: PreviewMusic,
    },
    {
        path: '/preview/video/:parentId/:fileId',
        name: 'PreviewVideo',
        component: PreviewVideo,
    },
    {
        path: '/404',
        component: Error404,
    },
    { path: '/:pathMatch(.*)*', redirect: '/404', hidden: true },
]

const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes,
})

const toIndexPageList = ['Login', 'Register', 'Forget'],
    whiteList = ['Login', 'Register', 'Forget', 'Share']

router.beforeEach((to, from) => {
    NProgress.start()
    if (!getToken()) {
        if (whiteList.includes(to.name)) return true
        return { name: 'Login', query: { redirect: to.fullPath } }
    }
    if (toIndexPageList.includes(to.name)) return { name: 'Home' }
    const redirect = from.query.redirect
    if (
        typeof redirect === 'string' &&
        redirect.startsWith('/') &&
        !redirect.startsWith('//') &&
        to.path !== redirect
    ) {
        return redirect
    }
    return true
})

router.afterEach(() => NProgress.done())
router.onError(() => NProgress.done())

export default router
