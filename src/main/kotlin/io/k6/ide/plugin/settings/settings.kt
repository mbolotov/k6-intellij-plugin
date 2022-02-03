package io.k6.ide.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.panel.ComponentPanelBuilder
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.XmlSerializerUtil
import io.k6.ide.plugin.actions.TOKEN_ENV_NAME
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

@State(name = "K6Settings", storages = [Storage("other.xml")])
class K6Settings : PersistentStateComponent<K6Settings> {
  var cloudToken: String = ""

  override fun getState(): K6Settings {
    return this
  }

  override fun loadState(`object`: K6Settings) {
    XmlSerializerUtil.copyBean(`object`, this)
  }

  companion object {
    val instance: K6Settings
      get() = ServiceManager.getService(K6Settings::class.java)
  }
}

class K6SettingsConfigurable : SearchableConfigurable, Configurable.NoScroll {
  private val myCloudTokenField = JBTextField()

  @Nls
  override fun getDisplayName(): String {
    return "k6"
  }

  override fun getHelpTopic(): String {
    return "preferences.K6"
  }

  override fun createComponent(): JComponent {
      val builder = FormBuilder.createFormBuilder()
      builder.addComponent(ComponentPanelBuilder(myCloudTokenField)
          .withLabel("Cloud token:")
          .withComment("If this field is left empty, k6 will look for an environment variable named `$TOKEN_ENV_NAME`.<br>No account yet? Register for a free trial <a href=\"https://k6.io/cloud\">here</a>.", false)
          .createPanel()
      )
      val borderLayout : LayoutManager = BorderLayout()
      val wrapper: JPanel = JBPanel<JBPanel<*>>(borderLayout)
      wrapper.add(builder.panel, BorderLayout.NORTH)
      return wrapper
  }


  override fun isModified(): Boolean {
    return myCloudTokenField.text != K6Settings.instance.cloudToken
  }

  @Throws(ConfigurationException::class)
  override fun apply() {
      K6Settings.instance.cloudToken = myCloudTokenField.text
  }

  override fun reset() {
      myCloudTokenField.text = K6Settings.instance.cloudToken
  }


  override fun getId(): String {
    return helpTopic
  }
}

