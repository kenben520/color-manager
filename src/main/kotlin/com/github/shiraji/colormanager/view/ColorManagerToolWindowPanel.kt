package com.github.shiraji.colormanager.view

import com.github.shiraji.colormanager.data.ColorManagerCell
import com.github.shiraji.colormanager.data.ColorManagerColorTag
import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDImage
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.xml.XmlTagImpl
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.ColorChooser
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.TextTransferable
import com.intellij.util.ui.UIUtil
import sun.swing.DefaultLookup
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.*

class ColorManagerToolWindowPanel(val project: Project) : SimpleToolWindowPanel(true, true), DataProvider, Disposable {

    val listModel: DefaultListModel<ColorManagerCell> = DefaultListModel()

    val colorMap: MutableMap<String, ColorManagerColorTag> = linkedMapOf()

    val FILTER_XML = listOf("AndroidManifest.xml", "strings.xml", "dimens.xml", "base_strings.xml", "pom.xml", "donottranslate-cldr.xml", "donottranslate-maps.xml", "common_strings.xml")

    var filterLibRes = true

    private val alarm = Alarm(Alarm.ThreadToUse.SHARED_THREAD)

    private val androidSdkPathRegex = Regex("platforms/android-\\d\\d*/data/res")

    var sortAsc = false

    init {
        setToolbar(createToolbarPanel())
        setContent(createContentPanel())
    }

    private fun createContentPanel(): JComponent {
        refreshListModel()
        val list = JBList(listModel)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val cell = ColorManagerToolWindowCell()
                val element = list?.model?.getElementAt(index) as? ColorManagerCell ?: return cell.rootPanel
                val colorName = element.colorName
                cell.colorNameLabel.text = colorName
                setColor(cell, colorName)
                cell.rootPanel.border =
                        if (cellHasFocus || isSelected)
                            DefaultLookup.getBorder(this, ui, "List.focusSelectedCellHighlightBorder") ?: DefaultLookup.getBorder(this, ui, "List.focusCellHighlightBorder")
                        else
                            DefaultLookup.getBorder(this, ui, "List.cellNoFocusBorder")
                return cell.rootPanel
            }

            private fun setColor(cell: ColorManagerToolWindowCell, colorName: String) {
                val colorManagerColorTag = colorMap[colorName]
                if (colorManagerColorTag == null) {
                    cell.colorCodeLabel.text = "$colorName not found"
                    return
                }
                setFileName(cell, colorManagerColorTag)
                setBackgroundAndColorCode(cell, colorManagerColorTag)
            }

            private fun setBackgroundAndColorCode(cell: ColorManagerToolWindowCell, colorManagerColorTag: ColorManagerColorTag) {
                val backgroundColor = colorManagerColorTag.color
                if (backgroundColor == null) {
                    cell.rootPanel.background = Color.WHITE
                    cell.colorCodeLabel.text = colorManagerColorTag.errorMessage ?: "'${colorManagerColorTag.tag.value.trimmedText}' not found"
                } else {
                    cell.rootPanel.background = backgroundColor
                    cell.colorCodeLabel.text = colorManagerColorTag.colorCode ?: "???"
                    val foregroundColor = if (isColorDark(backgroundColor)) Color.WHITE else Color.BLACK
                    cell.colorNameLabel.foreground = foregroundColor
                    cell.colorCodeLabel.foreground = foregroundColor
                    cell.fileNameLabel.foreground = foregroundColor
                }
            }

            // luma is calculated with the formula Y′ = 0.299 R′ + 0.587 G′ + 0.114 B′.
            // https://en.wikipedia.org/wiki/Luma_%28video%29
            private fun isColorDark(color: Color) = 1 - ( 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255 >= 0.5

            private fun setFileName(cell: ColorManagerToolWindowCell, colorManagerColorTag: ColorManagerColorTag) {
                val fileName = colorManagerColorTag.fileName
                cell.fileNameLabel.text = if (colorManagerColorTag.isInProject) fileName else "$fileName (in library)"
            }
        }
        // Google recommended height!!!
        list.fixedCellHeight = 48
        ListSpeedSearch(list)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                super.mouseClicked(e)
                e ?: return
                if (list.isEmpty || listModel.isEmpty || colorMap.isEmpty()) return

                if (list.minSelectionIndex < 0 || listModel.size() < list.minSelectionIndex) return

                if (SwingUtilities.isRightMouseButton(e)) {
                    handleRightClick(e)
                } else if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    handleDoubleClick()
                }
            }

            private fun handleDoubleClick() {
                val selectedColor = listModel.get(list.minSelectionIndex).colorName
                (colorMap[selectedColor]?.tag as? XmlTagImpl)?.navigate(true) ?: return
            }

            private fun handleRightClick(e: MouseEvent) {
                val selectedColor = listModel.get(list.minSelectionIndex).colorName
                val copyMenu = JMenuItem("Copy $selectedColor").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(TextTransferable(selectedColor))
                    }
                }

                val colorInfo = colorMap[selectedColor] ?: return
                val tag = colorInfo.tag
                val nameForXml = "${getXmlColorPrefix(colorInfo.isInAndroidSdk)}/${tag.getAttribute("name")?.value}"
                val copyMenuForXml = JMenuItem("Copy $nameForXml").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(TextTransferable(nameForXml))
                    }
                }

                val gotoMenu = JMenuItem("Go to $selectedColor").apply {
                    addActionListener {
                        colorInfo.let {
                            (it.tag as? XmlTagImpl)?.navigate(true)
                        }
                    }
                }

                val deleteMenu = JMenuItem("Delete $selectedColor").apply {
                    addActionListener {
                        val result = JOptionPane.showConfirmDialog(this@ColorManagerToolWindowPanel,
                                "Delete $selectedColor?",
                                "Delete Confirmation",
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.QUESTION_MESSAGE)
                        if (result != JOptionPane.YES_OPTION) return@addActionListener

                        CommandProcessor.getInstance().executeCommand(project, {
                            ApplicationManager.getApplication().runWriteAction {
                                colorInfo.tag.delete()
                                refreshListModel()
                            }
                        }, "Delete color", null)
                    }
                }

                val editMenu = JMenuItem("Edit $selectedColor").apply {
                    addActionListener {
                        val newColor = ColorChooser.chooseColor(this@ColorManagerToolWindowPanel, "Edit Color", colorInfo.color, true) ?: return@addActionListener
                        CommandProcessor.getInstance().executeCommand(project, {
                            ApplicationManager.getApplication().runWriteAction {
                                val alpha = newColor.alpha
                                val colorCode = if (alpha != 255)
                                    "#%02X%02X%02X%02X".format(alpha, newColor.red, newColor.green, newColor.blue)
                                else
                                    "#%02X%02X%02X".format(newColor.red, newColor.green, newColor.blue)
                                colorInfo.tag.value.text = colorCode
                            }
                            refreshListModel()
                        }, "Edit color", null)
                    }
                }

                JPopupMenu().run {
                    add(copyMenu)
                    add(copyMenuForXml)
                    add(gotoMenu)
                    if (colorInfo.isInProject) {
                        add(editMenu)
                        add(deleteMenu)
                    }
                    show(e.component, e.x, e.y)
                }
            }
        })

        installDnDAction(list)
        return ScrollPaneFactory.createScrollPane(list)
    }

    private fun installDnDAction(list: JBList) {
        DnDSupport.createBuilder(list).setBeanProvider { info ->
            if (list.minSelectionIndex < 0 || listModel.size() < list.minSelectionIndex) return@setBeanProvider null
            val file = getSelectedFile() ?: return@setBeanProvider null
            if (info.isMove) {
                if (file.fileType == XmlFileType.INSTANCE) {
                    DnDDragStartBean(StringSelection(getNameForXml(list)))
                } else {
                    DnDDragStartBean(StringSelection(listModel.get(list.minSelectionIndex).colorName))
                }
            } else {
                null
            }
        }.setImageProvider {
            if (list.minSelectionIndex < 0 || listModel.size() < list.minSelectionIndex) return@setImageProvider null
            val file = getSelectedFile() ?: return@setImageProvider null
            val label =
                    if (file.fileType == XmlFileType.INSTANCE) {
                        JLabel(getNameForXml(list))
                    } else {
                        JLabel(listModel.get(list.minSelectionIndex).colorName)
                    }
            label.isOpaque = true
            label.size = label.preferredSize
            val image = UIUtil.createImage(label.width, label.height, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.graphics as Graphics2D
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f)
            label.paint(g2)
            g2.dispose()
            DnDImage(image, Point(-image.getWidth(null), -image.getHeight(null)))
        }.setDisposableParent(this).install()
    }

    private fun getNameForXml(list: JBList): String? {
        val selectedColor = listModel.get(list.minSelectionIndex).colorName
        val colorInfo = colorMap[selectedColor] ?: return null
        val tag = colorInfo.tag
        return "${getXmlColorPrefix(colorInfo.isInAndroidSdk)}/${tag.getAttribute("name")?.value}"
    }

    private fun getSelectedFile(): VirtualFile? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return FileDocumentManager.getInstance().getFile(editor.document)
    }

    private fun getXmlColorPrefix(isInAndroidSdk: Boolean): String {
        return if (isInAndroidSdk) "@android:color" else "@color"
    }

    private fun createToolbarPanel(): JComponent? {
        val group = DefaultActionGroup()
        group.add(RefreshAction())
        group.add(FilterAction())
        group.add(SortAscAction())
        val actionToolBar = ActionManager.getInstance().createActionToolbar("ColorManager", group, true)
        return JBUI.Panels.simplePanel(actionToolBar.component)
    }

    override fun dispose() {
    }

    private fun initColorMap() {
        val psiManager = PsiManager.getInstance(project)

        FileTypeIndex.getFiles(XmlFileType.INSTANCE, ProjectScope.getProjectScope(project)).forEach {
            addToColorMap(psiManager, it, true, false)
        }

        if (!filterLibRes) {
            FileTypeIndex.getFiles(XmlFileType.INSTANCE, ProjectScope.getLibrariesScope(project)).forEach {
                addToColorMap(psiManager, it, isInProject = false, isInAndroidSdk = it.path.contains(androidSdkPathRegex))
            }
        }

        generateColors()
    }

    private fun generateColors() {
        val colorTextList = mutableListOf<String>()
        colorMap.keys.forEach {
            colorTextList.clear()
            val colorTag = colorMap[it] ?: return@forEach
            generateColorFromKey(colorTextList, colorTag, it)
        }
    }

    private fun generateColorFromKey(colorTextList: MutableList<String>, target: ColorManagerColorTag, colorName: String) {
        val colorTag = colorMap[colorName] ?: return
        val colorText = colorTag.tag.value.trimmedText
        if (colorTextList.contains(colorText)) {
            target.errorMessage = "Loop detected..."
            return
        }
        colorTextList.add(colorText)
        if (colorText.startsWith("@android:color/")) {
            generateColorFromKey(colorTextList, target, "R.color.${colorText.replace("@android:color/", "")}")
        } else if (colorText.startsWith("@color/")) {
            generateColorFromKey(colorTextList, target, "R.color.${colorText.replace("@color/", "")}")
        } else if (colorText.startsWith("#")) {
            target.colorCode = colorText
            target.color = when (colorText.length) {
                4 -> Color.decode("#${colorText[1]}${colorText[1]}${colorText[2]}${colorText[2]}${colorText[3]}${colorText[3]}")
                7 -> Color.decode(colorText)
                9 -> Color(java.lang.Long.decode(colorText).toInt(), true)
                else -> {
                    target.errorMessage = "Invalid color code: $colorText"
                    null
                }
            }
        } else {
            target.errorMessage = "Invalid format: $colorText"
        }
    }

    private fun addToColorMap(psiManager: PsiManager, virtualFile: VirtualFile, isInProject: Boolean, isInAndroidSdk: Boolean) {
        if (FILTER_XML.contains(virtualFile.name)) return
        val xmlFile = psiManager.findFile(virtualFile) as? XmlFile ?: return
        xmlFile.rootTag?.findSubTags("color")?.forEach {
            colorMap.put("R.color.${it.getAttribute("name")?.value}",
                    ColorManagerColorTag(
                            tag = it,
                            fileName = virtualFile.name,
                            isInProject = isInProject,
                            isInAndroidSdk = isInAndroidSdk))
        }
    }

    private fun refreshListModel() {
        try {
            setWaitCursor()
            resetColorMap()
            reloadListModel()
        } finally {
            restoreCursor()
        }
    }

    private fun resetColorMap() {
        colorMap.clear()
        initColorMap()
    }

    private fun reloadListModel() {
        listModel.removeAllElements()
        val keys = if (sortAsc) colorMap.keys.sorted() else colorMap.keys
        keys.forEach {
            val element = ColorManagerCell(it, colorMap[it]?.colorCode ?: "")
            listModel.addElement(element)
        }
    }

    private fun restoreCursor() {
        alarm.cancelAllRequests()
        cursor = Cursor.getDefaultCursor()
    }

    private fun setWaitCursor() {
        alarm.addRequest({ cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) }, 100)
    }

    inner class RefreshAction() : AnAction("Reload colors", "Reload colors", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent?) {
            ApplicationManager.getApplication().invokeLater {
                refreshListModel()
            }
        }
    }

    inner class FilterAction() : ToggleAction("Filter library resource colors", "Filter library resource colors", AllIcons.General.Filter) {

        override fun isSelected(e: AnActionEvent?) = filterLibRes

        override fun setSelected(e: AnActionEvent?, state: Boolean) {
            filterLibRes = state
            refreshListModel()
        }
    }

    inner class SortAscAction() : ToggleAction("Sort alphabetically", "Sort alphabetically", AllIcons.ObjectBrowser.Sorted) {
        override fun isSelected(e: AnActionEvent?) = sortAsc

        override fun setSelected(e: AnActionEvent?, state: Boolean) {
            sortAsc = state
            reloadListModel()
        }
    }
}
