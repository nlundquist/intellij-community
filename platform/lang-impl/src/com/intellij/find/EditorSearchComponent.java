/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * @author max, zajac
 */
package com.intellij.find;

import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.find.editorHeaderActions.*;
import com.intellij.find.impl.FindManagerImpl;
import com.intellij.find.impl.livePreview.LiveOccurrence;
import com.intellij.find.impl.livePreview.LivePreview;
import com.intellij.find.impl.livePreview.LivePreviewControllerBase;
import com.intellij.find.impl.livePreview.SearchResults;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.LightColors;
import com.intellij.ui.NonFocusableCheckBox;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.*;
import java.util.regex.Pattern;

public class EditorSearchComponent extends JPanel implements DataProvider, SelectionListener, SearchResults.SearchResultsListener,
                                                             FindModel.FindModelObserver {
  private static final int MATCHES_LIMIT = 10000;
  private final JLabel myMatchInfoLabel;
  private final LinkLabel myClickToHighlightLabel;
  private final Project myProject;
  private final Editor myEditor;
  private DefaultActionGroup myActionsGroup;

  public JTextField getSearchField() {
    return mySearchField;
  }

  public JTextField getReplaceField() {
    return myReplaceField;
  }

  private final JTextField mySearchField;
  private JTextField myReplaceField;
  private final Color myDefaultBackground;

  private JButton myReplaceButton;
  private JButton myReplaceAllButton;
  private JButton myExcludeButton;

  private final Color GRADIENT_C1;

  private final Color GRADIENT_C2;
  private static final Color BORDER_COLOR = new Color(0x87, 0x87, 0x87);
  public static final Color COMPLETION_BACKGROUND_COLOR = new Color(235, 244, 254);
  private static final Color FOCUS_CATCHER_COLOR = new Color(0x9999ff);
  private final JComponent myToolbarComponent;
  private com.intellij.openapi.editor.event.DocumentAdapter myDocumentListener;
  private final JCheckBox myCbRegexp;

  private final MyLivePreviewController myLivePreviewController;
  private final LivePreview myLivePreview;


  private boolean myListeningSelection = false;
  private SearchResults mySearchResults;
  private Balloon myOptionsBalloon;

  private final FindModel myFindModel;
  private JCheckBox myCbMatchCase;
  private JPanel myReplacementPane;

  public JComponent getToolbarComponent() {
    return myToolbarComponent;
  }

  private static FindModel createDefaultFindModel(Project p, Editor e) {
    FindModel findModel = new FindModel();
    findModel.copyFrom(FindManager.getInstance(p).getFindInFileModel());
    if (e.getSelectionModel().hasSelection()) {
      String selectedText = e.getSelectionModel().getSelectedText();
      if (selectedText != null) {
        findModel.setStringToFind(selectedText);
      }
    }
    return findModel;
  }

  public EditorSearchComponent(Editor editor, Project project) {
    this(editor, project, createDefaultFindModel(project, editor));
  }

  @Nullable
  public Object getData(@NonNls final String dataId) {
    if (PlatformDataKeys.EDITOR_EVEN_IF_INACTIVE.is(dataId)) {
      return myEditor;
    }
    return null;
  }

  @Override
  public void searchResultsUpdated(SearchResults sr) {
    int count = sr.getActualFound();
    if (mySearchField.getText().isEmpty()) {
      updateUIWithEmptyResults();
    } else {
      if (count <= mySearchResults.getMatchesLimit()) {
        myClickToHighlightLabel.setVisible(false);

        if (count > 0) {
          setRegularBackground();
          if (count > 1) {
            myMatchInfoLabel.setText(count + " matches");
          }
          else {
            myMatchInfoLabel.setText("1 match");
          }
        }
        else {
          setNotFoundBackground();
          myMatchInfoLabel.setText("No matches");
        }
      }
      else {
        setRegularBackground();
        myMatchInfoLabel.setText("More than " + mySearchResults.getMatchesLimit() + " matches");
        myClickToHighlightLabel.setVisible(true);
        boldMatchInfo();
      }
    }

    updateExcludeStatus();
  }

  @Override
  public void cursorMoved() {
    updateExcludeStatus();
  }

  @Override
  public void editorChanged(SearchResults sr, Editor oldEditor) {  }

  public EditorSearchComponent(final Editor editor, final Project project, FindModel findModel) {
    super(new BorderLayout(0, 0));
    myFindModel = findModel;

    GRADIENT_C1 = getBackground();
    GRADIENT_C2 = new Color(Math.max(0, GRADIENT_C1.getRed() - 0x18), Math.max(0, GRADIENT_C1.getGreen() - 0x18), Math.max(0, GRADIENT_C1.getBlue() - 0x18));

    myProject = project;
    myEditor = editor;

    mySearchResults = new SearchResults(myEditor);
    myLivePreview = new LivePreview(mySearchResults);

    myLivePreviewController = new MyLivePreviewController();

    mySearchResults.addListener(this);
    setMatchesLimit(MATCHES_LIMIT);


    final JPanel leadPanel = createLeadPane();
    add(leadPanel, BorderLayout.WEST);

    mySearchField = createTextField();

    leadPanel.add(mySearchField);
    mySearchField.putClientProperty("AuxEditorComponent", Boolean.TRUE);

    myDefaultBackground = mySearchField.getBackground();

    myActionsGroup = new DefaultActionGroup("search bar", false);
    myActionsGroup.add(new ShowHistoryAction(mySearchField, this));
    myActionsGroup.add(new PrevOccurrenceAction(this));
    myActionsGroup.add(new NextOccurrenceAction(this));
    myActionsGroup.add(new FindAllAction(this));

    myActionsGroup.addAction(new ToggleWholeWordsOnlyAction(this)).setAsSecondary(true);
    if (FindManagerImpl.ourHasSearchInCommentsAndLiterals) {
      myActionsGroup.addAction(new ToggleInCommentsAction(this)).setAsSecondary(true);
      myActionsGroup.addAction(new ToggleInLiteralsOnlyAction(this)).setAsSecondary(true);
    }
    myActionsGroup.addAction(new TogglePreserveCaseAction(this)).setAsSecondary(true);
    myActionsGroup.addAction(new ToggleSelectionOnlyAction(this)).setAsSecondary(true);

    final ActionToolbar tb = ActionManager.getInstance().createActionToolbar("SearchBar", myActionsGroup, true);
    tb.setLayoutPolicy(ActionToolbar.AUTO_LAYOUT_POLICY);
    myToolbarComponent = tb.getComponent();
    myToolbarComponent.setBorder(null);
    myToolbarComponent.setOpaque(false);
    leadPanel.add(myToolbarComponent);

    myCbMatchCase = new NonFocusableCheckBox("Case sensitive");
    myCbRegexp = new NonFocusableCheckBox("Regex");

    leadPanel.add(myCbMatchCase);
    leadPanel.add(myCbRegexp);

    myFindModel.addObserver(new FindModel.FindModelObserver() {
      @Override
      public void findModelChanged(FindModel findModel) {
        syncFindModels(FindManager.getInstance(myProject).getFindInFileModel(), myFindModel);
        String stringToFind = myFindModel.getStringToFind();
        if (!wholeWordsApplicable(stringToFind)) {
          myFindModel.setWholeWordsOnly(false);
        }
        updateUIWithFindModel();
        updateResults(true);
      }
    });

    FindManager.getInstance(myProject).getFindInFileModel().addObserver(this);

    updateUIWithFindModel();

    myCbMatchCase.setMnemonic('C');
    myCbRegexp.setMnemonic('e');

    setSmallerFontAndOpaque(myCbMatchCase);
    setSmallerFontAndOpaque(myCbRegexp);


    myCbMatchCase.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final boolean b = myCbMatchCase.isSelected();
        FindManager.getInstance(myProject).getFindInFileModel().setCaseSensitive(b);
        FindSettings.getInstance().setLocalCaseSensitive(b);
      }
    });

    myCbRegexp.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final boolean b = myCbRegexp.isSelected();
        FindManager.getInstance(myProject).getFindInFileModel().setRegularExpressions(b);
      }
    });

    JPanel tailPanel = new NonOpaquePanel(new BorderLayout(5, 0));
    JPanel tailContainer = new NonOpaquePanel(new BorderLayout(5, 0));
    tailContainer.add(tailPanel, BorderLayout.EAST);
    add(tailContainer, BorderLayout.CENTER);

    myMatchInfoLabel = new JLabel();
    setSmallerFontAndOpaque(myMatchInfoLabel);

    myClickToHighlightLabel = new LinkLabel("Click to highlight", null, new LinkListener() {
      @Override
      public void linkSelected(LinkLabel aSource, Object aLinkData) {
        setMatchesLimit(Integer.MAX_VALUE);
        updateResults(true);
      }
    });
    setSmallerFontAndOpaque(myClickToHighlightLabel);
    myClickToHighlightLabel.setVisible(false);

    JLabel closeLabel = new JLabel(" ", IconLoader.getIcon("/actions/cross.png"), SwingConstants.RIGHT);
    closeLabel.addMouseListener(new MouseAdapter() {
      public void mousePressed(final MouseEvent e) {
        close();
      }
    });

    closeLabel.setToolTipText("Close search bar (Escape)");

    JPanel labelsPanel = new NonOpaquePanel(new FlowLayout());

    labelsPanel.add(myMatchInfoLabel);
    labelsPanel.add(myClickToHighlightLabel);
    tailPanel.add(labelsPanel, BorderLayout.CENTER);
    tailPanel.add(closeLabel, BorderLayout.EAST);

    configureTextField(mySearchField);
    mySearchField.getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        setMatchesLimit(MATCHES_LIMIT);
        String text = mySearchField.getText();
        if (!StringUtil.isEmpty(text)) {
          myFindModel.setStringToFind(text);
          updateResults(true);
        } else {
          nothingToSearchFor();
        }
      }
    });

    setSmallerFont(mySearchField);
    mySearchField.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        if ("".equals(mySearchField.getText())) {
          close();
        }
        else {
          requestFocus(myEditor.getContentComponent());
          addTextToRecents(EditorSearchComponent.this.mySearchField);
        }
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK),
                                         JComponent.WHEN_FOCUSED);

    final String initialText = myFindModel.getStringToFind();

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      public void run() {
        setInitialText(initialText);
      }
    });

    new VariantsCompletionAction(this, mySearchField); // It registers a shortcut set automatically on construction

    new SwitchToFind(this);
    new SwitchToReplace(this);
  }

  @Override
  public void findModelChanged(FindModel findModel) {
    syncFindModels(myFindModel, findModel);
  }

  public boolean isRegexp() {
    return myFindModel.isRegularExpressions();
  }

  public void setRegexp(boolean val) {
    myFindModel.setRegularExpressions(val);
  }

  public FindModel getFindModel() {
    return myFindModel;
  }

  private static void syncFindModels(FindModel to, FindModel from) {
    to.setCaseSensitive(from.isCaseSensitive());
    to.setWholeWordsOnly(from.isWholeWordsOnly());
    to.setRegularExpressions(from.isRegularExpressions());
    to.setInCommentsOnly(from.isInCommentsOnly());
    to.setInStringLiteralsOnly(from.isInStringLiteralsOnly());
  }

  private void updateFindModelWithUI() {
    myFindModel.setCaseSensitive(myCbMatchCase.isSelected());
    myFindModel.setRegularExpressions(myCbRegexp.isSelected());
    myFindModel.setFromCursor(false);
    myFindModel.setSearchHighlighters(true);

  }

  private void updateUIWithFindModel() {
    myCbMatchCase.setSelected(myFindModel.isCaseSensitive());
    myCbRegexp.setSelected(myFindModel.isRegularExpressions());

    String stringToFind = myFindModel.getStringToFind();

    if (!StringUtil.equals(stringToFind, mySearchField.getText())) {
      mySearchField.setText(stringToFind);
    }

    setTrackingSelection(!myFindModel.isGlobal());

    if (myFindModel.isReplaceState() && myReplacementPane == null) {
      configureReplacementPane();
    } else if (!myFindModel.isReplaceState() && myReplacementPane != null) {
      remove(myReplacementPane);
      myReplacementPane = null;
    }
    if (myFindModel.isReplaceState()) {
      String stringToReplace = myFindModel.getStringToReplace();
      if (!StringUtil.equals(stringToReplace, myReplaceField.getText())) {
        myReplaceField.setText(stringToReplace);
      }
      updateExcludeStatus();
    }
  }

  private boolean wholeWordsApplicable(String stringToFind) {
    return !stringToFind.startsWith(" ") &&
           !stringToFind.startsWith("\t") &&
           !stringToFind.endsWith(" ") &&
           !stringToFind.endsWith("\t");
  }

  private static FindModel createFindModel(FindModel findInFileModel, boolean isReplace) {
    FindModel result = new FindModel();
    result.copyFrom(findInFileModel);
    if (isReplace) {
      result.setReplaceState(isReplace);
    }
    return result;
  }

  private void setMatchesLimit(int value) {
    mySearchResults.setMatchesLimit(value);
  }

  private void configureReplacementPane() {
    myReplacementPane = createLeadPane();
    myReplaceField = createTextField();
    configureTextField(myReplaceField);
    myReplaceField.getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        setMatchesLimit(MATCHES_LIMIT);
        myFindModel.setStringToReplace(myReplaceField.getText());
      }
    });
    myReplaceField.registerKeyboardAction(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        myLivePreviewController.performReplace();
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
    myReplaceField.setText(myFindModel.getStringToReplace());
    myReplacementPane.add(myReplaceField);
    add(myReplacementPane, BorderLayout.SOUTH);

    myReplaceButton = new JButton("Replace");
    myReplaceButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        myLivePreviewController.performReplace();
      }
    });
    myReplaceButton.setMnemonic('p');

    myReplaceAllButton = new JButton("Replace all");
    myReplaceAllButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        myLivePreviewController.performReplaceAll();
      }
    });
    myReplaceAllButton.setMnemonic('a');

    myExcludeButton = new JButton("");

    myExcludeButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        myLivePreviewController.exclude();
      }
    });
    myExcludeButton.setMnemonic('l');

    myReplacementPane.add(myReplaceButton);
    myReplacementPane.add(myReplaceAllButton);
    myReplacementPane.add(myExcludeButton);

    setSmallerFontAndOpaque(myReplaceButton);
    setSmallerFontAndOpaque(myReplaceAllButton);
    setSmallerFontAndOpaque(myExcludeButton);
    
    setSmallerFont(myReplaceField);
    myReplaceField.putClientProperty("AuxEditorComponent", Boolean.TRUE);
    new VariantsCompletionAction(this, myReplaceField);
  }

  private void updateExcludeStatus() {
    if (myExcludeButton != null) {
      LiveOccurrence cursor = mySearchResults.getCursor();
      myExcludeButton.setText(cursor == null || !mySearchResults.isExcluded(cursor) ? "Exclude" : "Include");
      myReplaceAllButton.setEnabled(mySearchResults.hasMatches());
      if (cursor != null) {
        myExcludeButton.setEnabled(true);
        myReplaceButton.setEnabled(true);
      } else {
        myExcludeButton.setEnabled(false);
        myReplaceButton.setEnabled(false);
      }
    }
  }

  private void setTrackingSelection(boolean b) {
    if (b) {
      if (!myListeningSelection) {
        myEditor.getSelectionModel().addSelectionListener(this);
      }
    } else {
      if (myListeningSelection) {
        myEditor.getSelectionModel().removeSelectionListener(this);
      }
    }
    myListeningSelection = b;
  }

  private NonOpaquePanel createLeadPane() {
    return new NonOpaquePanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
  }

  private JTextField createTextField() {
    return new JTextField() {
      protected void paintBorder(final Graphics g) {
        super.paintBorder(g);

        if (!(UIUtil.isUnderAquaLookAndFeel() || UIUtil.isUnderQuaquaLookAndFeel()) && isFocusOwner()) {
          final Rectangle bounds = getBounds();
          g.setColor(FOCUS_CATCHER_COLOR);
          g.drawRect(0, 0, bounds.width - 1, bounds.height - 1);
        }
      }
    };
  }

  private void configureTextField(final JTextField searchField) {
    searchField.setColumns(25);

    searchField.addFocusListener(new FocusListener() {
      public void focusGained(final FocusEvent e) {
        searchField.repaint();
      }

      public void focusLost(final FocusEvent e) {
        searchField.repaint();
      }
    });



    searchField.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        close();
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);

    searchField.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        if (StringUtil.isEmpty(searchField.getText())) {
          showHistory(false, searchField);
        }
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED);

  }

  public void setInitialText(final String initialText) {
    final String text = initialText != null ? initialText : "";
    if (text.contains("\n")) {
      myFindModel.setRegularExpressions(true);
      setTextInField(StringUtil.escapeToRegexp(text));
    }
    else {
      setTextInField(text);
    }
    mySearchField.selectAll();
  }

  private void requestFocus(Component c) {
    IdeFocusManager.getInstance(myProject).requestFocus(c, true);
  }

  public void searchBackward() {
    moveCursor(SearchResults.Direction.UP);

    addTextToRecents(mySearchField);
  }

  public void searchForward() {
    moveCursor(SearchResults.Direction.DOWN);
    addTextToRecents(mySearchField);
  }

  private void addTextToRecents(JTextField textField) {
    final String text = textField.getText();
    if (text.length() > 0) {
      if (textField == mySearchField) {
        FindSettings.getInstance().addStringToFind(text);
      } else {
        FindSettings.getInstance().addStringToReplace(text);
      }
    }
  }

  @Override
  public void selectionChanged(SelectionEvent e) {
    updateResults(true);
  }

  private void moveCursor(SearchResults.Direction direction) {
    myLivePreviewController.moveCursor(direction, true);
  }

  private static void setSmallerFontAndOpaque(final JComponent component) {
    setSmallerFont(component);
    component.setOpaque(false);
  }

  private static void setSmallerFont(final JComponent component) {
    if (SystemInfo.isMac) {
      Font f = component.getFont();
      component.setFont(f.deriveFont(f.getStyle(), f.getSize() - 2));
    }
  }

  public void requestFocus() {
    requestFocus(mySearchField);
  }

  private void close() {
    if (myEditor.getSelectionModel().hasSelection()) {
      myEditor.getCaretModel().moveToOffset(myEditor.getSelectionModel().getSelectionStart());
      myEditor.getSelectionModel().removeSelection();
    }
    IdeFocusManager.getInstance(myProject).requestFocus(myEditor.getContentComponent(), false);
    FindManager.getInstance(myProject).getFindInFileModel().removeObserver(this);
    mySearchResults.dispose();
    myLivePreview.cleanUp();
    myEditor.setHeaderComponent(null);
    addTextToRecents(mySearchField);
  }

  @Override
  public void addNotify() {
    super.addNotify();

    myDocumentListener = new com.intellij.openapi.editor.event.DocumentAdapter() {
      public void documentChanged(final com.intellij.openapi.editor.event.DocumentEvent e) {
        updateResults(false);
      }
    };

    myEditor.getDocument().addDocumentListener(myDocumentListener);

    if (myLivePreview != null) {
      myLivePreviewController.updateInBackground(mySearchResults.getFindModel(), false);
    }
  }

  public void removeNotify() {
    super.removeNotify();

    if (myDocumentListener != null) {
      myEditor.getDocument().removeDocumentListener(myDocumentListener);
      myDocumentListener = null;
    }
    mySearchResults.dispose();
    myLivePreview.cleanUp();
    if (myListeningSelection) {
      myEditor.getSelectionModel().removeSelectionListener(this);
    }
  }

  private void updateResults(final boolean allowedToChangedEditorSelection) {
    myMatchInfoLabel.setFont(myMatchInfoLabel.getFont().deriveFont(Font.PLAIN));
    final String text = mySearchField.getText();
    if (text.length() == 0) {
      nothingToSearchFor();
    }
    else {

      setRegularBackground();
      if (myFindModel.isRegularExpressions()) {
        try {
          Pattern.compile(text);
        }
        catch (Exception e) {
          setNotFoundBackground();
          myMatchInfoLabel.setText("Incorrect regular expression");
          boldMatchInfo();
          myClickToHighlightLabel.setVisible(false);
          return;
        }
      }


      final FindManager findManager = FindManager.getInstance(myProject);
      if (allowedToChangedEditorSelection) {
        findManager.setFindWasPerformed();
        FindModel copy = new FindModel();
        copy.copyFrom(myFindModel);
        copy.setReplaceState(false);
        findManager.setFindNextModel(copy);
      }
      
      myLivePreviewController.updateInBackground(myFindModel, allowedToChangedEditorSelection);
    }
  }

  private void nothingToSearchFor() {
    updateUIWithEmptyResults();
    mySearchResults.clear();
  }

  private void updateUIWithEmptyResults() {
    setRegularBackground();
    myMatchInfoLabel.setText("");
    myClickToHighlightLabel.setVisible(false);
  }

  private void boldMatchInfo() {
    myMatchInfoLabel.setFont(myMatchInfoLabel.getFont().deriveFont(Font.BOLD));
  }

  private void setRegularBackground() {
    mySearchField.setBackground(myDefaultBackground);
  }

  private void setNotFoundBackground() {
    mySearchField.setBackground(LightColors.RED);
  }

  public String getTextInField() {
    return mySearchField.getText();
  }

  public void setTextInField(final String text) {
    mySearchField.setText(text);
    if (!StringUtil.isEmpty(text)) {
      myFindModel.setStringToFind(text);
    }
  }

  public boolean hasMatches() {
    return myLivePreview != null && myLivePreview.hasMatches();
  }

  public void showHistory(final boolean byClickingToolbarButton, JTextField textField) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed("find.recent.search");
    FindSettings settings = FindSettings.getInstance();
    String[] recents = textField == mySearchField ?  settings.getRecentFindStrings() : settings.getRecentReplaceStrings();
    Utils.showCompletionPopup(byClickingToolbarButton ? myToolbarComponent : null, new JBList(ArrayUtil.reverseArray(recents)),
                              "Recent Searches",
                              textField);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    final Graphics2D g2d = (Graphics2D) g;

    g2d.setPaint(new GradientPaint(0, 0, GRADIENT_C1, 0, getHeight(), GRADIENT_C2));
    g2d.fillRect(1, 1, getWidth(), getHeight() - 1);
    
    g.setColor(BORDER_COLOR);
    g2d.setPaint(null);
    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
  }

  private class MyLivePreviewController extends LivePreviewControllerBase {
    public MyLivePreviewController() {
      super(EditorSearchComponent.this.mySearchResults, EditorSearchComponent.this.myLivePreview);
    }

    @Override
    public void getFocusBack() {
      if (myFindModel != null && myFindModel.isReplaceState()) {
        requestFocus(myReplaceField);
      } else {
        requestFocus(mySearchField);
      }
    }

    public void performReplace() {
      String replacement = getStringToReplace(myEditor, mySearchResults.getCursor());
      performReplace(mySearchResults.getCursor(), replacement, myEditor);
      getFocusBack();
      addTextToRecents(myReplaceField);
    }

    public void exclude() {
      mySearchResults.exclude(mySearchResults.getCursor());
    }

    public void performReplaceAll() {
      performReplaceAll(myEditor);
    }
  }
}
