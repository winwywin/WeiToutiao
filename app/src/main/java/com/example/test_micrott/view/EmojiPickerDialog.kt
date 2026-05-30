package com.example.test_micrott.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.test_micrott.R
import com.example.test_micrott.databinding.DialogEmojiPickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 表情选择底部弹窗。
 *
 * 使用 BottomSheetDialogFragment + ViewPager2 + TabLayout，
 * 每个 tab 对应一个表情分类，页面内是 7 列 emoji 网格。
 *
 * 构造参数 onEmojiPicked 在用户点击表情后回调，由 MainActivity 插入到 EditText。
 */
class EmojiPickerDialog(
    private val onEmojiPicked: (String) -> Unit,
) : BottomSheetDialogFragment() {

    private var _binding: DialogEmojiPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogEmojiPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 包裹回调：选表情后自动关闭弹窗
        val wrappedCallback: (String) -> Unit = { emoji ->
            onEmojiPicked(emoji)
            dismiss()
        }

        binding.vpEmojiPages.adapter = EmojiPagerAdapter(this, wrappedCallback)
        TabLayoutMediator(binding.tabEmojiCategories, binding.vpEmojiPages) { tab, position ->
            tab.text = EmojiData.categories[position].label
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ========================================================================
    // ViewPager2 适配器：每个页面是一个 RecyclerView 网格
    // ========================================================================

    private inner class EmojiPagerAdapter(
        fragment: BottomSheetDialogFragment,
        private val emojiCallback: (String) -> Unit,
    ) : FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = EmojiData.categories.size

        override fun createFragment(position: Int): EmojiPageFragment {
            return EmojiPageFragment.newInstance(
                EmojiData.categories[position].emojis,
                emojiCallback,
            )
        }
    }
}

// ========================================================================
// 每个分类的表情页面 Fragment
// ========================================================================

class EmojiPageFragment : androidx.fragment.app.Fragment() {

    companion object {
        private const val ARG_EMOJIS = "emojis"

        fun newInstance(emojis: List<String>, onEmojiClick: (String) -> Unit): EmojiPageFragment {
            return EmojiPageFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_EMOJIS, ArrayList(emojis))
                }
                this.onEmojiClick = onEmojiClick
            }
        }
    }

    private var onEmojiClick: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rv = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(context, 7)
            val emojis = arguments?.getStringArrayList(ARG_EMOJIS)?.toList() ?: emptyList()
            adapter = EmojiGridAdapter(emojis) { emoji ->
                onEmojiClick?.invoke(emoji)
            }
        }
        return rv
    }
}

// ========================================================================
// 表情数据：分类 + emoji 字符列表
// ========================================================================

object EmojiData {

    data class Category(val label: String, val emojis: List<String>)

    val categories = listOf(
        Category("😊 表情", listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
            "🙂", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗",
            "😚", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗",
            "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶",
            "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪",
            "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🥵",
            "🥶", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "🧐",
            "😕", "😟", "🙁", "😮", "😯", "😲", "😳", "🥺",
            "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱",
            "😖", "😣", "😞", "😓", "😩", "😫", "🥱", "😤",
            "😡", "😠", "🤬", "😈", "👿", "💀", "☠️", "💩",
            "🤡", "👹", "👺", "👻", "👽", "👾", "🤖", "😺",
        )),
        Category("🐱 动物", listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
            "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺",
            "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞",
            "🐜", "🦟", "🦗", "🕷️", "🦂", "🐢", "🐍", "🦎",
            "🦖", "🦕", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡",
            "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅",
            "🐆", "🦓", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪",
            "🐫", "🦒", "🦘", "🐃", "🐂", "🐄", "🐎", "🐖",
            "🐏", "🐑", "🦙", "🐐", "🦌", "🐕", "🐩", "🦮",
            "🐕‍🦺", "🐈", "🐓", "🦃", "🦚", "🦜", "🦢", "🕊️",
            "🐇", "🦝", "🦨", "🦡", "🦦", "🦥", "🐁", "🐀",
        )),
        Category("🌿 自然", listOf(
            "🌵", "🎄", "🌲", "🌳", "🌴", "🌱", "🌿", "☘️",
            "🍀", "🎍", "🎋", "🍃", "🍂", "🍁", "🍄", "🐚",
            "🌾", "💐", "🌷", "🌹", "🥀", "🌺", "🌸", "🌼",
            "🌻", "🌞", "🌝", "🌛", "🌜", "🌚", "🌕", "🌖",
            "🌗", "🌘", "🌑", "🌒", "🌓", "🌔", "🌙", "🌎",
            "🌍", "🌏", "💫", "⭐", "🌟", "✨", "⚡", "☁️",
            "⛅", "⛈️", "🌤️", "🌥️", "🌦️", "🌧️", "🌨️", "🌩️",
            "🌪️", "🌫️", "🌬️", "🌀", "🌈", "🌂", "☂️", "☔",
            "⛱️", "⚡", "❄️", "☃️", "⛄", "☄️", "🔥", "💧",
            "🌊", "🎃", "🎄", "🎆", "🎇", "🧨", "✨", "🎈",
            "🎉", "🎊", "🎋", "🎍", "🎎", "🎏", "🎐", "🎑",
        )),
        Category("🍔 食物", listOf(
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
            "🍓", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝",
            "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️", "🌽",
            "🥕", "🧄", "🧅", "🥔", "🍠", "🥐", "🍞", "🥖",
            "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓",
            "🥩", "🍗", "🍖", "🦴", "🌭", "🍔", "🍟", "🍕",
            "🥪", "🥙", "🧆", "🌮", "🌯", "🥗", "🥘", "🥫",
            "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🦪",
            "🍤", "🍙", "🍚", "🍘", "🍥", "🥠", "🥮", "🍢",
            "🍡", "🍧", "🍨", "🍦", "🥧", "🧁", "🍰", "🎂",
            "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪", "🌰",
            "🥜", "🍯", "🥛", "🍼", "☕", "🍵", "🧃", "🥤",
            "🍶", "🍺", "🍻", "🥂", "🍷", "🥃", "🍸", "🍹",
            "🧉", "🍾", "🧊", "🥄", "🍴", "🍽️", "🥣", "🥡",
        )),
        Category("⚽ 活动", listOf(
            "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉",
            "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍",
            "🏏", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿", "🥊",
            "🥋", "🎽", "⛸️", "🥌", "🛷", "🛼", "🎿", "⛷️",
            "🏂", "🪂", "🏋️", "🤼", "🤸", "🤺", "⛹️", "🤾",
            "🏌️", "🏇", "🧘", "🏄", "🏊", "🤽", "🚣", "🧗",
            "🚵", "🚴", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️",
            "🏵️", "🎗️", "🎫", "🎟️", "🎪", "🤹", "🎭", "🩰",
            "🎨", "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷",
            "🎺", "🎸", "🪕", "🎻", "🎲", "♟️", "🎯", "🎳",
            "🎮", "👾", "🎰", "🧩", "♠️", "♥️", "♦️", "🃏",
        )),
        Category("🚗 旅行", listOf(
            "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
            "🚒", "🚐", "🚚", "🚛", "🚜", "🛴", "🚲", "🛵",
            "🏍️", "🛺", "🚨", "🚔", "🚍", "🚘", "🚖", "🚡",
            "🚠", "🚟", "🚃", "🚋", "🚞", "🚝", "🚄", "🚅",
            "🚈", "🚂", "🚆", "🚇", "🚊", "🚉", "✈️", "🛫",
            "🛬", "🛩️", "💺", "🛰️", "🚀", "🛸", "🚁", "🛶",
            "⛵", "🚤", "🛥️", "🛳️", "⛴️", "🚢", "⚓", "⛽",
            "🚧", "🚦", "🚥", "🚏", "🗺️", "🗿", "🗽", "🗼",
            "🏰", "🏯", "🏟️", "🎡", "🎢", "🎠", "⛲", "⛱️",
            "🏖️", "🏝️", "🏜️", "🌋", "⛰️", "🏔️", "🗻", "🏕️",
            "⛺", "🏠", "🏡", "🏘️", "🏚️", "🏗️", "🏭", "🏢",
            "🏬", "🏣", "🏤", "🏥", "🏦", "🏨", "🏪", "🏫",
        )),
        Category("💡 物品", listOf(
            "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️",
            "🕹️", "🗜️", "💽", "💾", "💿", "📀", "📼", "📷",
            "📸", "📹", "🎥", "📽️", "🎞️", "📞", "☎️", "📟",
            "📠", "📺", "📻", "🎙️", "🎚️", "🎛️", "🧭", "⏱️",
            "⏲️", "⏰", "🕰️", "⌛", "📡", "🔋", "🔌", "💡",
            "🔦", "🕯️", "🪔", "🧯", "🛢️", "💸", "💵", "💴",
            "💶", "💷", "💰", "💳", "💎", "⚖️", "🧰", "🔧",
            "🔨", "⚒️", "🛠️", "⛏️", "🔩", "⚙️", "🧱", "⛓️",
            "🧲", "🔫", "💣", "🧨", "🪓", "🔪", "🗡️", "⚔️",
            "🛡️", "🚬", "⚰️", "⚱️", "🏺", "🔮", "📿", "🧿",
            "💈", "⚗️", "🔭", "🔬", "🕳️", "💊", "💉", "🩸",
            "🩹", "🩺", "🌡️", "🧹", "🧺", "🧻", "🚽", "🚿",
        )),
        Category("💯 符号", listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖",
            "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️", "☸️",
            "✡️", "🔯", "🕎", "☯️", "☦️", "🛐", "⛎", "♈",
            "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐",
            "♑", "♒", "♓", "🆔", "⚛️", "🉑", "☢️", "☣️",
            "📴", "📳", "🈶", "🈚", "🈸", "🈺", "🈷️", "✴️",
            "🆚", "💮", "🉐", "㊙️", "㊗️", "🈴", "🈵", "🈹",
            "🈲", "🅰️", "🅱️", "🆎", "🆑", "🅾️", "🆘", "❌",
            "⭕", "🛑", "⛔", "📛", "🚫", "💯", "💢", "♨️",
            "🚷", "🚯", "🚳", "🚱", "🔞", "📵", "🚭", "❗",
            "❕", "❓", "❔", "‼️", "⁉️", "🔅", "🔆", "〽️",
            "⚠️", "🚸", "🔱", "⚜️", "🔰", "♻️", "✅", "🈯",
            "💹", "❇️", "✳️", "❎", "🌐", "💠", "Ⓜ️", "🌀",
            "💤", "🏧", "🚾", "♿", "🅿️", "🈳", "🈂️", "🛂",
            "🛃", "🛄", "🛅", "🚹", "🚺", "🚼", "⚧", "🚻",
            "🚮", "🎦", "📶", "🈁", "🔣", "ℹ️", "🔤", "🔡",
            "🔠", "🆖", "🆗", "🆙", "🆒", "🆕", "🆓", "0️⃣",
            "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣",
            "9️⃣", "🔟", "🔢", "#️⃣", "*️⃣", "⏏️", "▶️", "⏸️",
            "⏯️", "⏹️", "⏺️", "⏭️", "⏮️", "⏩", "⏪", "⏫",
            "⏬", "◀️", "🔼", "🔽", "➡️", "⬅️", "⬆️", "⬇️",
            "↗️", "↘️", "↙️", "↖️", "↕️", "↔️", "↩️", "↪️",
            "⤴️", "⤵️", "🔀", "🔁", "🔂", "🔄", "🔃", "🎵",
            "🎶", "➕", "➖", "➗", "✖️", "♾️", "💲", "💱",
            "™️", "©️", "®️", "〰️", "➰", "➿", "🔚", "🔙",
            "🔛", "🔝", "🔜", "✔️", "☑️", "🔘", "🔴", "🟠",
            "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🔺",
            "🔻", "🔸", "🔹", "🔶", "🔷", "🔳", "🔲", "▪️",
            "▫️", "◾", "◽", "◼️", "◻️", "🟥", "🟧", "🟨",
            "🟩", "🟦", "🟪", "⬛", "⬜", "🟫", "🔈", "🔇",
            "🔉", "🔊", "🔔", "🔕", "📣", "📢", "💬", "💭",
            "🗯️", "♨️", "🀀", "🀁", "🀂", "🀃", "🀄",
        )),
    )
}
