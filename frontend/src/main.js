import { createApp } from 'vue'
import {
    ElAutocomplete,
    ElBreadcrumb,
    ElBreadcrumbItem,
    ElButton,
    ElCard,
    ElCol,
    ElDialog,
    ElDivider,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElForm,
    ElFormItem,
    ElInput,
    ElLink,
    ElLoading,
    ElMenu,
    ElMenuItem,
    ElMessage,
    ElMessageBox,
    ElNotification,
    ElOption,
    ElProgress,
    ElRadio,
    ElRadioGroup,
    ElRow,
    ElSelect,
    ElStep,
    ElSteps,
    ElTable,
    ElTableColumn,
    ElTooltip,
    ElTree,
} from 'element-plus'
import 'element-plus/dist/index.css'
import 'font-awesome/css/font-awesome.min.css'
import './styles/cospace.css'
import App from './App.vue'
import router from './router/index.js'
import { copyText } from './utils/clipboard.js'

const app = createApp(App)
app.use(router)
const components = [
    ElAutocomplete,
    ElBreadcrumb,
    ElBreadcrumbItem,
    ElButton,
    ElCard,
    ElCol,
    ElDialog,
    ElDivider,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElForm,
    ElFormItem,
    ElInput,
    ElLink,
    ElLoading,
    ElMenu,
    ElMenuItem,
    ElOption,
    ElProgress,
    ElRadio,
    ElRadioGroup,
    ElRow,
    ElSelect,
    ElStep,
    ElSteps,
    ElTable,
    ElTableColumn,
    ElTooltip,
    ElTree,
]
components.forEach((component) => app.use(component))
Object.assign(app.config.globalProperties, {
    $loading: ElLoading.service,
    $message: ElMessage,
    $confirm: ElMessageBox.confirm,
    $notify: ElNotification,
})
app.config.globalProperties.$clipboard = copyText
app.mount('#app')
